/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.macho;


import static com.facebook.buck.cxx.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.apple.CodeSigning;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.cxx.MungingDebugPathSanitizer;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.FakeAppleDeveloperEnvironment;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;

public class ObjectPathsAbsolutifierIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  private DebugPathSanitizer getDebugPathSanitizer() {
    // this was stolen from the implementation detail of AppleCxxPlatforms
    return new MungingDebugPathSanitizer(
        250,
        File.separatorChar,
        Paths.get("."),
        ImmutableBiMap.of());
  }

  @Test
  public void testAbsolutifyingPathsForIntel64Bit() throws IOException, InterruptedException {
    Flavor platformFlavor = ImmutableFlavor.of("iphonesimulator-x86_64");
    runAndCheckAbsolutificationWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testAbsolutifyingPathsForIntel32Bit() throws IOException, InterruptedException {
    Flavor platformFlavor = ImmutableFlavor.of("iphonesimulator-i386");
    runAndCheckAbsolutificationWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testAbsolutifyingPathsForArm64Bit() throws IOException, InterruptedException {
    Flavor platformFlavor = ImmutableFlavor.of("iphoneos-arm64");
    runAndCheckAbsolutificationWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testAbsolutifyingPathsForArm32Bit() throws IOException, InterruptedException {
    Flavor platformFlavor = ImmutableFlavor.of("iphoneos-armv7");
    runAndCheckAbsolutificationWithPlatformFlavor(platformFlavor);
  }

  private void runAndCheckAbsolutificationWithPlatformFlavor(
      Flavor platformFlavor) throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_platform", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
        .withAppendedFlavors(platformFlavor);
    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand(
            "build",
            "--config",
            "cxx.cflags=-g",
            target.getFullyQualifiedName());
    result.assertSuccess();

    Path relativeSanitizedObjectFilePath = BuildTargets
        .getGenPath(
            filesystem,
            target.withFlavors(
                platformFlavor,
                ImmutableFlavor.of("compile-" + sanitize("main.c.o"))),
            "%s")
        .resolve("main.c.o");

    Path relativeSanitizedSourceFilePath = BuildTargets
        .getGenPath(
            filesystem,
            target.withFlavors(
                platformFlavor,
                ImmutableFlavor.of("preprocess-" + sanitize("main.c.i"))),
            "%s");

    Path sanitizedBinaryPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(
                    platformFlavor,
                    CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR,
                    LinkerMapMode.DEFAULT_MODE.getFlavor()),
                "%s"));
    Path unsanizitedBinaryPath = workspace.getPath(
        filesystem.getBuckPaths().getScratchDir().resolve(sanitizedBinaryPath.getFileName()));

    // this was stolen from the implementation detail of AppleCxxPlatforms
    DebugPathSanitizer sanitizer = getDebugPathSanitizer();

    String oldCompDirValue = sanitizer.getCompilationDirectory();
    String newCompDirValue = workspace.getDestPath().toString();

    result = workspace.runBuckCommand(
        "machoutils",
        "absolutify_object_paths",
        "--binary",
        sanitizedBinaryPath.toString(),
        "--output",
        unsanizitedBinaryPath.toString(),
        "--old_compdir",
        oldCompDirValue,
        "--new_compdir",
        newCompDirValue);
    result.assertSuccess();

    ProcessExecutor.Result sanitizedResult =
        workspace.runCommand("nm", "-a", sanitizedBinaryPath.toString());
    ProcessExecutor.Result unsanitizedResult =
        workspace.runCommand("nm", "-a", unsanizitedBinaryPath.toString());

    String sanitizedOutput = sanitizedResult.getStdout().orElse("");
    String unsanitizedOutput = unsanitizedResult.getStdout().orElse("");

    // check that weird buck comp dir is not present anymore
    assertThat(
        sanitizedOutput,
        containsString("SO .///////////////////////////////////////////////////////////////"));
    assertThat(
        unsanitizedOutput,
        not(containsString("SO .///////////////////////////////////////////////////////////////")));

    // check that relative path to object file is not present anymore
    assertThat(sanitizedOutput, containsString("OSO ./buck-out"));
    assertThat(unsanitizedOutput, not(containsString("OSO ./buck-out")));

    // check that relative path to source file is not present anymore
    assertThat(sanitizedOutput, containsString("SOL Apps/TestApp/main.c"));
    assertThat(unsanitizedOutput, not(containsString("SOL Apps/TestApp/main.c")));

    // check that absolute path to included source file is correct
    assertThat(
        unsanitizedOutput,
        containsString("SOL " + newCompDirValue + "/Apps/TestApp/main.c"));
    // check that absolute path to object file is correct
    assertThat(
        unsanitizedOutput,
        containsString("OSO " + newCompDirValue + "/buck-out/bin/" +
            relativeSanitizedObjectFilePath.toString()));
    // check that absolute path to source file is correct
    assertThat(
        unsanitizedOutput,
        containsString("SO " + newCompDirValue + "/" + relativeSanitizedSourceFilePath.toString()));
  }

  private boolean checkCodeSigning(Path absoluteBundlePath)
      throws IOException, InterruptedException {
    if (!Files.exists(absoluteBundlePath)) {
      throw new NoSuchFileException(absoluteBundlePath.toString());
    }

    return CodeSigning.hasValidSignature(
        new DefaultProcessExecutor(new TestConsole()),
        absoluteBundlePath);
  }

  private boolean checkCodeSignatureMatchesBetweenFiles(Path file1, Path file2)
      throws IOException, InterruptedException {
    if (!Files.exists(file1)) {
      throw new NoSuchFileException(file1.toString());
    }
    if (!Files.exists(file2)) {
      throw new NoSuchFileException(file2.toString());
    }

    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());

    ProcessExecutor.Result result1 = processExecutor.launchAndExecute(
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("codesign", "-vvvv", "-d", file1.toString())).build(),
        EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT, ProcessExecutor.Option.IS_SILENT),
        /* stdin */ Optional.empty(),
        /* timeOutMs */ Optional.empty(),
        /* timeOutHandler */ Optional.empty());
    ProcessExecutor.Result result2 = processExecutor.launchAndExecute(
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("codesign", "-vvvv", "-d", file1.toString())).build(),
        EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT, ProcessExecutor.Option.IS_SILENT),
        /* stdin */ Optional.empty(),
        /* timeOutMs */ Optional.empty(),
        /* timeOutHandler */ Optional.empty());

    String stderr1 = result1.getStderr().orElse("");
    String stderr2 = result2.getStderr().orElse("");

    // skip first line as it has a path to the binary
    Assert.assertThat(stderr1, startsWith("Executable="));
    Assert.assertThat(stderr2, startsWith("Executable="));

    stderr1 = stderr1.substring(stderr1.indexOf("\n"));
    stderr2 = stderr2.substring(stderr2.indexOf("\n"));

    return stderr1.equals(stderr2);
  }

  @Test
  public void testAbsolutifyingPathsPreservesCodeSignature() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_with_codesigning",
        tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#iphoneos-arm64,dwarf");
    workspace.runBuckCommand(
        "build",
        "--config",
        "cxx.cflags=-g",
        target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));

    Path sanitizedBinaryPath = appPath.resolve(target.getShortName());
    assertThat(Files.exists(sanitizedBinaryPath), equalTo(true));
    assertThat(checkCodeSigning(sanitizedBinaryPath), equalTo(true));
    assertThat(checkCodeSigning(appPath), equalTo(true));

    Path unsanizitedBinaryPath = workspace.getPath(
        filesystem.getBuckPaths().getScratchDir()
            .resolve(sanitizedBinaryPath.getParent())
            .resolve(sanitizedBinaryPath.getFileName()));

    // copy bundle
    MoreFiles.copyRecursively(sanitizedBinaryPath.getParent(), unsanizitedBinaryPath.getParent());

    DebugPathSanitizer sanitizer = getDebugPathSanitizer();

    String oldCompDirValue = sanitizer.getCompilationDirectory();
    String newCompDirValue = workspace.getDestPath().toString();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "machoutils",
        "absolutify_object_paths",
        "--binary",
        sanitizedBinaryPath.toString(),
        "--output",
        unsanizitedBinaryPath.toString(),
        "--old_compdir",
        oldCompDirValue,
        "--new_compdir",
        newCompDirValue);
    result.assertSuccess();

    assertThat(Files.exists(unsanizitedBinaryPath), equalTo(true));
    // of course signature should be broken
    assertThat(checkCodeSigning(unsanizitedBinaryPath), equalTo(false));
    // but it should stay unchanged from what is used to be
    assertThat(
        checkCodeSignatureMatchesBetweenFiles(unsanizitedBinaryPath, sanitizedBinaryPath),
        equalTo(true));
  }
}
