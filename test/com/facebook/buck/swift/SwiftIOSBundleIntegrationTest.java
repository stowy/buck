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

package com.facebook.buck.swift;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.ApplePlatform;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;

import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SwiftIOSBundleIntegrationTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void simpleApplicationBundle() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR),
        is(true));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_swift_application_bundle",
        tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

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
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));
  }

  @Test
  public void swiftWithSwiftDependenciesBuildsSomething() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR),
        is(true));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "swift_on_swift",
        tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:parent");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    target = workspace.newBuildTarget("//:libparent");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    target = workspace.newBuildTarget("//:ios-sos#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));
  }

  @Test
  public void swiftLibraryWhenLinkStyleIsNotSharedDoesNotProduceDylib() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "swift_on_swift", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget parentDynamicTarget = BuildTargetFactory.newInstance("//:ios-parent-dynamic")
        .withAppendedFlavors(ImmutableFlavor.of("iphonesimulator-x86_64"));

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        parentDynamicTarget.getFullyQualifiedName(),
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path binaryOutput = workspace.resolve(
        BuildTargets.getGenPath(
            filesystem,
            parentDynamicTarget.withAppendedFlavors(
                LinkerMapMode.DEFAULT_MODE.getFlavor(),
                CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR),
            "%s"));
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    assertThat(
        workspace.runCommand("file", binaryOutput.toString()).getStdout().get(),
        containsString("executable"));
    assertThat(
        workspace.runCommand("otool", "-hv", binaryOutput.toString()).getStdout().get(),
        containsString("X86_64"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        not(containsString("libdep1.dylib")));

    Path dep1Output = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("iosdep1#iphonesimulator-x86_64,swift-compile")
        .resolve("libiosdep1.dylib");
    assertThat(Files.notExists(dep1Output), CoreMatchers.is(true));
  }

  @Test
  public void swiftLibraryWhenLinkStyleIsSharedShouldProduceDylib() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "swift_on_swift", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget parentDynamicTarget = BuildTargetFactory.newInstance("//:ios-parent-dynamic")
        .withAppendedFlavors(ImmutableFlavor.of("iphonesimulator-x86_64"));

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        parentDynamicTarget.getFullyQualifiedName(),
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path binaryOutput = workspace.resolve(
        BuildTargets.getGenPath(
            filesystem,
            parentDynamicTarget.withAppendedFlavors(
                LinkerMapMode.DEFAULT_MODE.getFlavor(),
                CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR),
            "%s"));
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    assertThat(
        workspace.runCommand("file", binaryOutput.toString()).getStdout().get(),
        containsString("executable"));
    assertThat(
        workspace.runCommand("otool", "-hv", binaryOutput.toString()).getStdout().get(),
        containsString("X86_64"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("libiosdep1.dylib"));

    Path parentOutput = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("ios-parent-dynamic#iphonesimulator-x86_64,swift-compile")
        .resolve("ios_parent_dynamic.swiftmodule");
    assertThat(Files.exists(parentOutput), CoreMatchers.is(true));

    BuildTarget iosdep1Target = BuildTargetFactory.newInstance("//:iosdep1")
        .withAppendedFlavors(
            ImmutableFlavor.of("iphonesimulator-x86_64"),
            LinkerMapMode.DEFAULT_MODE.getFlavor());
    Path iosdep1TargetOutput = workspace.resolve(
        BuildTargets.getGenPath(
            filesystem,
            iosdep1Target,
            "%s"));
    assertThat(
        Files.exists(iosdep1TargetOutput.resolve("libiosdep1.dylib")),
        CoreMatchers.is(true));
  }

  @Test
  public void testSwiftSharedLibraryCustomSoname() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "swift_on_swift", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        ":dep1-soname#iphonesimulator-x86_64,shared",
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path binaryOutput = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("dep1-soname#iphonesimulator-x86_64," +
            LinkerMapMode.DEFAULT_MODE.getFlavor().getName())
        .resolve("custom-soname");
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    assertThat(
        workspace.runCommand("file", binaryOutput.toString()).getStdout().get(),
        containsString("shared library"));
    assertThat(
        workspace.runCommand("otool", "-hv", binaryOutput.toString()).getStdout().get(),
        containsString("X86_64"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("@rpath/custom-soname"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        not(containsString("@rpath/dep1-soname")));
  }

  @Test
  public void testSwiftPreferredLinkage() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "swift_on_swift", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    workspace.replaceFileContents(
        "BUCK",
        "preferred_linkage = 'any', # iosdep1 preferred_linkage anchor",
        "preferred_linkage = 'static'");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        ":ios-parent-dynamic#iphonesimulator-x86_64",
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path binaryOutput = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("ios-parent-dynamic#" +
            CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR.getName() +
            ",iphonesimulator-x86_64," +
            LinkerMapMode.DEFAULT_MODE.getFlavor().getName());
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    Path dep1Output = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("iosdep1#iphonesimulator-x86_64")
        .resolve("libiosdep1.dylib");
    assertThat(Files.exists(dep1Output), CoreMatchers.is(false));

    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        not(containsString("libiosdep1.dylib")));

    assertThat(
        workspace.runCommand("nm", binaryOutput.toString()).getStdout().orElse(""),
        containsString("baz"));
  }

  @Test
  public void swiftDependsOnObjCRunsAndPrintsMessage() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR),
        is(true));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "swift_on_objc", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:binary#iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    target = workspace.newBuildTarget("//:bundle#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));
  }

}
