/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.Unzip;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

public class BuiltinApplePackageIntegrationTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  private static boolean isDirEmpty(final Path directory) throws IOException {
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(directory)) {
      return !dirStream.iterator().hasNext();
    }
  }

  @Test
  public void packageHasProperStructure() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget appTarget =
        BuildTargetFactory.newInstance("//:DemoApp#no-debug,no-include-frameworks");
    workspace.runBuckCommand(
        "build",
        appTarget.getUnflavoredBuildTarget().getFullyQualifiedName()).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(appTarget.getFullyQualifiedName());

    workspace.runBuckCommand("clean").assertSuccess();

    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache(appTarget.getFullyQualifiedName());
    workspace.getBuildLog().assertTargetBuiltLocally(packageTarget.getFullyQualifiedName());

    Path templateDir = TestDataHelper.getTestDataScenario(
        this,
        "simple_application_bundle_no_debug");

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(BuildTargets.getGenPath(filesystem, packageTarget, "%s.ipa")));
    zipInspector.assertFileExists("Payload/DemoApp.app/DemoApp");
    zipInspector.assertFileDoesNotExist("WatchKitSupport");
    zipInspector.assertFileDoesNotExist("WatchKitSupport2");
    zipInspector.assertFileContents(
        "Payload/DemoApp.app/PkgInfo",
        new String(
            Files.readAllBytes(
                templateDir.resolve("DemoApp_output.expected/DemoApp.app/PkgInfo.expected")),
            UTF_8));
  }

  @Test
  public void packageHasProperStructureForSwift() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
      this,
      "simple_application_bundle_swift_no_debug",
      tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(packageTarget.getFullyQualifiedName());

    ZipInspector zipInspector = new ZipInspector(
      workspace.getPath(BuildTargets.getGenPath(filesystem, packageTarget, "%s.ipa")));
    zipInspector.assertFileExists("SwiftSupport/iphonesimulator/libswiftCore.dylib");
  }

  @Test
  public void swiftSupportIsOnlyAddedIfPackageContainsSwiftCode() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
      this,
      "simple_application_bundle_no_debug",
      tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(packageTarget.getFullyQualifiedName());

    ZipInspector zipInspector = new ZipInspector(
      workspace.getPath(BuildTargets.getGenPath(filesystem, packageTarget, "%s.ipa")));
    zipInspector.assertFileDoesNotExist("SwiftSupport");
  }

  @Test
  public void packageHasProperStructureForWatch20() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "watch_application_bundle",
        tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[apple]\n  watchsimulator_target_sdk_version = 2.0",
        ".buckconfig.local");
    packageHasProperStructureForWatchHelper(workspace, true);
  }

  @Test
  public void packageHasProperStructureForWatch21() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "watch_application_bundle",
        tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[apple]\n  watchsimulator_target_sdk_version = 2.1",
        ".buckconfig.local");
    packageHasProperStructureForWatchHelper(workspace, false);
  }

  private void packageHasProperStructureForWatchHelper(
      ProjectWorkspace workspace,
      boolean shouldHaveStubInsideBundle)
      throws IOException, InterruptedException {
    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    Path destination = workspace.getDestPath();
    Unzip.extractZipFile(
        workspace.getPath(BuildTargets.getGenPath(filesystem, packageTarget, "%s.ipa")),
        destination,
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    Path stubOutsideBundle = destination.resolve("WatchKitSupport2/WK");
    assertTrue(Files.isExecutable(stubOutsideBundle));
    assertTrue(Files.isDirectory(destination.resolve("Symbols")));
    assertTrue(isDirEmpty(destination.resolve("Symbols")));

    if (shouldHaveStubInsideBundle) {
      Path stubInsideBundle = destination.resolve(
          "Payload/DemoApp.app/Watch/DemoWatchApp.app/_WatchKitStub/WK");
      assertTrue(Files.exists(stubInsideBundle));
      assertEquals(
          new String(Files.readAllBytes(stubInsideBundle)),
          new String(Files.readAllBytes(stubOutsideBundle)));
    }
  }

  @Test
  public void packageHasProperStructureForLegacyWatch() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "legacy_watch_application_bundle",
        tmp);
    workspace.setUp();
    BuildTarget packageTarget = BuildTargetFactory.newInstance("//:DemoAppPackage");
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    Path destination = workspace.getDestPath();
    Unzip.extractZipFile(
        workspace.getPath(BuildTargets.getGenPath(filesystem, packageTarget, "%s.ipa")),
        destination,
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    Path stub = destination.resolve("WatchKitSupport/WK");
    assertTrue(Files.isExecutable(stub));
    assertFalse(Files.isDirectory(destination.resolve("Symbols")));
  }


  @Test
  public void packageSupportsFatBinaries() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();

    BuildTarget packageTarget = BuildTargetFactory.newInstance(
        "//:DemoAppPackage#iphonesimulator-i386,iphonesimulator-x86_64")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    workspace.runBuckCommand("build", packageTarget.getFullyQualifiedName()).assertSuccess();

    Unzip.extractZipFile(
        workspace.getPath(BuildTargets.getGenPath(filesystem, packageTarget, "%s.ipa")),
        workspace.getDestPath(),
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());

    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "lipo",
                    "-info",
                    workspace.getDestPath().resolve("Payload/DemoApp.app/DemoApp").toString()))
            .build();

    // Specify that stdout is expected, or else output may be wrapped in Ansi escape chars.
    Set<ProcessExecutor.Option> options =
        EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT, ProcessExecutor.Option.IS_SILENT);

    ProcessExecutor.Result result = executor.launchAndExecute(
        processExecutorParams,
        options,
        /* stdin */ Optional.empty(),
        /* timeOutMs */ Optional.empty(),
        /* timeOutHandler */ Optional.empty());

    assertEquals(result.getExitCode(), 0);
    assertTrue(result.getStdout().isPresent());
    String output = result.getStdout().get();
    assertTrue(output.contains("i386"));
    assertTrue(output.contains("x86_64"));
  }
}
