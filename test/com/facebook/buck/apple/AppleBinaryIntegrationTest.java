/*
 * Copyright 2014-present Facebook, Inc.
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

import static com.facebook.buck.cxx.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class AppleBinaryIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testAppleBinaryBuildsBinaryWithLinkerMap() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
        .withAppendedFlavors(
            LinkerMapMode.LINKER_MAP.getFlavor());
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR),
            "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(Paths.get(outputPath.toString() + "-LinkMap.txt")), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryUsesDefaultsFromConfig() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_config_default_platform", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
        .withAppendedFlavors(
            LinkerMapMode.LINKER_MAP.getFlavor(),
            ImmutableFlavor.of("iphoneos-arm64"));
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget implicitTarget = target.withAppendedFlavors(
        CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR);
    Path outputPath = workspace.getPath(BuildTargets.getGenPath(filesystem, implicitTarget, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(Paths.get(outputPath.toString() + "-LinkMap.txt")), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
    assertThat(
        workspace.runCommand("otool", "-hv", outputPath.toString()).getStdout().get(),
        containsString("ARM64"));
  }

  @Test
  public void testAppleBinaryUsesDefaultsFromArgs() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_platform", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
        .withAppendedFlavors(
            ImmutableFlavor.of("iphoneos-arm64"),
            LinkerMapMode.LINKER_MAP.getFlavor());
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget implicitTarget = target.withAppendedFlavors(
        CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR);
    Path outputPath = workspace.getPath(BuildTargets.getGenPath(filesystem, implicitTarget, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(Paths.get(outputPath.toString() + "-LinkMap.txt")), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
    assertThat(
        workspace.runCommand("otool", "-hv", outputPath.toString()).getStdout().get(),
        containsString("ARM64"));
  }

  @Test
  public void testAppleBinaryUsesPlatformLinkerFlags() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory
        .newInstance("//Apps/TestApp:TestAppWithNonstandardMain")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR),
            "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }


  @Test
  public void testAppleBinaryAppBuildsAppWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#app");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget = target.withFlavors(
        AppleBinaryDescription.APP_FLAVOR,
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
        AppleDebugFormat.DWARF_AND_DSYM.getFlavor());
    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(outputPath.resolve("Info.plist")), is(true));

    Path dsymPath = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app.dSYM"));
    assertThat(Files.exists(dsymPath), is(true));
    assertThat(
        workspace.runCommand(
            "file",
            outputPath.resolve(appTarget.getShortName()).toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryAppBuildsAppWithoutDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#app,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget = target.withFlavors(
        AppleBinaryDescription.APP_FLAVOR,
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
        AppleDebugFormat.NONE.getFlavor());
    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(outputPath.resolve("Info.plist")), is(true));

    Path dsymPath = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app.dSYM"));
    assertThat(Files.exists(dsymPath), is(false));
  }

  @Test
  public void testAppleBinaryWithSystemFrameworksBuildsSomething() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_system_frameworks_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Apps/TestApp:TestApp#macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(
                CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR,
                LinkerMapMode.DEFAULT_MODE.getFlavor()),
            "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyBuildsSomething() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
        .withAppendedFlavors(
            ImmutableFlavor.of("macosx-x86_64"),
            LinkerMapMode.DEFAULT_MODE.getFlavor());
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR),
            "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyBuildsApp() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Apps/TestApp:TestApp#app,macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path bundlePath = workspace.getPath(
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(
                AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
            "%s/TestApp.app"));
    assertThat(Files.exists(bundlePath), is(true));
    Path binaryPath = bundlePath.resolve("Contents/MacOS/TestApp");
    assertThat(Files.exists(binaryPath), is(true));
    assertThat(
        workspace.runCommand("file", binaryPath.toString()).getStdout().get(),
        containsString("executable"));
    Path frameworkBundlePath = bundlePath.resolve("Contents/Frameworks/TestLibrary.framework");
    assertThat(Files.exists(frameworkBundlePath), is(true));
    Path frameworkBinaryPath = frameworkBundlePath.resolve("TestLibrary");
    assertThat(Files.exists(frameworkBinaryPath), is(true));
    assertThat(
        workspace.runCommand("file", frameworkBinaryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyWithSystemFrameworksBuildsSomething()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_with_system_frameworks_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
        .withAppendedFlavors(
            ImmutableFlavor.of("macosx-x86_64"),
            LinkerMapMode.DEFAULT_MODE.getFlavor());
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(BuildTargets.getGenPath(
        filesystem,
        target.withAppendedFlavors(CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR),
        "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleLibraryPropagatesExportedPlatformLinkerFlags()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//Apps/TestApp:BadTestApp");
    buildResult.assertFailure();
    String stderr = buildResult.getStderr();
    assertTrue(stderr.contains("bad-flag"));
  }

  @Test
  public void testAppleBinaryHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_header_symlink_tree", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#default," +
        CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = tmp.getRoot().toRealPath();

    Path inputPath = projectRoot.resolve(
        buildTarget.getBasePath());
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(filesystem, buildTarget, "%s"));

    assertIsSymbolicLink(
        outputPath.resolve("Header.h"),
        inputPath.resolve("Header.h"));
    assertIsSymbolicLink(
        outputPath.resolve("TestApp/Header.h"),
        inputPath.resolve("Header.h"));
  }

  @Test
  public void testAppleBinaryWithHeaderMaps() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_header_maps", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(
                CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR,
                LinkerMapMode.DEFAULT_MODE.getFlavor()),
            "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleXcodeError() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    String expectedError =
        "Apps/TestApp/main.c:2:3: error: use of undeclared identifier 'SomeType'\n" +
            "  SomeType a;\n" +
            "  ^\n";
    String expectedWarning =
        "Apps/TestApp/main.c:3:10: warning: implicit conversion from 'double' to 'int' changes " +
            "value from 0.42 to 0 [-Wliteral-conversion]\n" +
            "  return 0.42;\n" +
            "  ~~~~~~ ^~~~\n";
    String expectedSummary = "1 warning and 1 error generated.\n";

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_xcode_error", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//Apps/TestApp:TestApp");
    buildResult.assertFailure();
    String stderr = buildResult.getStderr();

    assertTrue(
        stderr.contains(expectedError) &&
            stderr.contains(expectedWarning) &&
            stderr.contains(expectedSummary));
  }

  @Test
  public void testAppleBinaryIsHermetic() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_is_hermetic", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
            .withAppendedFlavors(
                ImmutableFlavor.of("iphonesimulator-x86_64"),
                LinkerMapMode.DEFAULT_MODE.getFlavor());
    ProjectWorkspace.ProcessResult first = workspace.runBuckCommand(
        workspace.getPath("first"),
        "build",
        target.getFullyQualifiedName());
    first.assertSuccess();

    ProjectWorkspace.ProcessResult second = workspace.runBuckCommand(
        workspace.getPath("second"),
        "build",
        target.getFullyQualifiedName());
    second.assertSuccess();

    Path outputPath = BuildTargets.getGenPath(
        filesystem,
        target.withFlavors(
            ImmutableFlavor.of("iphonesimulator-x86_64"),
            ImmutableFlavor.of("compile-" + sanitize("TestClass.m.o"))),
        "%s/TestClass.m.o");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(outputPath)),
        workspace.getPath(Paths.get("second").resolve(outputPath)));
    outputPath = BuildTargets.getGenPath(
        filesystem,
        target.withAppendedFlavors(CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR),
        "%s");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(outputPath)),
        workspace.getPath(Paths.get("second").resolve(outputPath)));
  }

  private void runTestAppleBinaryWithDebugFormatIsHermetic(AppleDebugFormat debugFormat)
      throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_is_hermetic", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Apps/TestApp:TestApp#iphonesimulator-x86_64," + debugFormat.getFlavor().getName());
    ProjectWorkspace.ProcessResult first = workspace.runBuckCommand(
        workspace.getPath("first"),
        "build",
        target.getFullyQualifiedName());
    first.assertSuccess();

    ProjectWorkspace.ProcessResult second = workspace.runBuckCommand(
        workspace.getPath("second"),
        "build",
        target.getFullyQualifiedName());
    second.assertSuccess();

    Path outputPath = BuildTargets.getGenPath(
        filesystem,
        target.withFlavors(
            ImmutableFlavor.of("iphonesimulator-x86_64"),
            ImmutableFlavor.of("compile-" + sanitize("TestClass.m.o"))),
        "%s/TestClass.m.o");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(outputPath)),
        workspace.getPath(Paths.get("second").resolve(outputPath)));
    outputPath = BuildTargets.getGenPath(
        filesystem,
        target
            .withAppendedFlavors(
                CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR,
                LinkerMapMode.LINKER_MAP.getFlavor())
            .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors()),
        "%s");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(outputPath)),
        workspace.getPath(Paths.get("second").resolve(outputPath)));

    if (debugFormat != AppleDebugFormat.DWARF) {
      Path strippedPath = BuildTargets.getGenPath(
          filesystem,
          target
              .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
              .withAppendedFlavors(
                  StripStyle.NON_GLOBAL_SYMBOLS.getFlavor(),
                  CxxStrip.RULE_FLAVOR,
                  LinkerMapMode.LINKER_MAP.getFlavor()),
          "%s");
      MoreAsserts.assertContentsEqual(
          workspace.getPath(Paths.get("first").resolve(strippedPath)),
          workspace.getPath(Paths.get("second").resolve(strippedPath)));
    }
  }

  @Test
  public void testAppleBinaryWithDwarfDebugFormatIsHermetic() throws IOException {
    runTestAppleBinaryWithDebugFormatIsHermetic(AppleDebugFormat.DWARF);
  }

  @Test
  public void testAppleBinaryWithDwarfAndDsymDebugFormatIsHermetic() throws IOException {
    runTestAppleBinaryWithDebugFormatIsHermetic(AppleDebugFormat.DWARF_AND_DSYM);
  }

  @Test
  public void testAppleBinaryWithNoneDebugFormatIsHermetic() throws IOException {
    runTestAppleBinaryWithDebugFormatIsHermetic(AppleDebugFormat.NONE);
  }

  @Test
  public void testAppleBinaryBuildsFatBinaries() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance(
        "//:DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64,no-linkermap");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path output = workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"));
    assertThat(Files.exists(output), is(true));
    assertThat(
        workspace.runCommand("file", output.toString()).getStdout().get(),
        containsString("executable"));
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", output.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(
        lipoVerifyResult.getStderr().orElse(""),
        0,
        lipoVerifyResult.getExitCode());
  }

  @Test
  public void testAppleBinaryBuildsFatBinariesWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance(
        "//:DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64,no-linkermap");
    BuildTarget targetToBuild = target
        .withAppendedFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor());
    BuildTarget dsymTarget = target.withAppendedFlavors(AppleDsym.RULE_FLAVOR);
    workspace.runBuckCommand("build", targetToBuild.getFullyQualifiedName()).assertSuccess();
    Path output = workspace.getPath(
        AppleDsym.getDsymOutputPath(dsymTarget.withoutFlavors(
            LinkerMapMode.FLAVOR_DOMAIN.getFlavors()),
            filesystem));
    AppleDsymTestUtil
        .checkDsymFileHasDebugSymbolsForMainForConcreteArchitectures(
            workspace,
            output,
            Optional.of(ImmutableList.of("i386", "x86_64")));
  }

  @Test
  public void testFlavoredAppleBundleBuildsAndDsymFileCreatedAndBinaryIsStripped()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#dwarf-and-dsym");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format_for_binaries=none",
        target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path output = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(target.getShortName() + ".app.dSYM")
            .resolve("Contents/Resources/DWARF")
            .resolve(target.getShortName()));
    assertThat(Files.exists(output), equalTo(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, output);

    Path binaryOutput = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(target.getShortName() + ".app")
            .resolve(target.getShortName()));
    assertThat(Files.exists(binaryOutput), equalTo(true));

    ProcessExecutor.Result hasSymbol = workspace.runCommand("nm", binaryOutput.toString());
    String stdout = hasSymbol.getStdout().orElse("");
    assertThat(stdout, Matchers.not(containsString("t -[AppDelegate window]")));
    assertThat(stdout, containsString("U _UIApplicationMain"));
  }

  @Test
  public void testFlavoredAppleBundleBuildsWithDwarfDebugFormatAndBinaryIsUnstripped()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#dwarf");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.DWARF.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path output = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(target.getShortName() + ".app")
            .resolve(target.getShortName()));
    assertThat(Files.exists(output), equalTo(true));
    ProcessExecutor.Result hasSymbol = workspace.runCommand("nm", output.toString());
    String stdout = hasSymbol.getStdout().orElse("");
    assertThat(stdout, containsString("t -[AppDelegate window]"));
    assertThat(stdout, containsString("U _UIApplicationMain"));
  }

  @Test
  public void testBuildingWithDwarfProducesAllCompileRulesOnDisk() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    Flavor platformFlavor = ImmutableFlavor.of("iphonesimulator-x86_64");

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp")
        .withAppendedFlavors(AppleDebugFormat.DWARF.getFlavor());
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:DemoAppBinary")
        .withAppendedFlavors(platformFlavor, AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.DWARF.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);

    Path binaryOutput = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(target.getShortName() + ".app")
            .resolve(target.getShortName()));

    Path delegateFileOutput = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                binaryTarget.withFlavors(
                    platformFlavor,
                    ImmutableFlavor.of("compile-" + sanitize("AppDelegate.m.o"))),
                "%s")
            .resolve("AppDelegate.m.o"));

    Path mainFileOutput = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                binaryTarget.withFlavors(
                    platformFlavor,
                    ImmutableFlavor.of("compile-" + sanitize("main.m.o"))),
                "%s")
            .resolve("main.m.o"));

    assertThat(Files.exists(binaryOutput), equalTo(true));
    assertThat(Files.exists(delegateFileOutput), equalTo(true));
    assertThat(Files.exists(mainFileOutput), equalTo(true));
  }

  @Test
  public void testBuildingWithNoDebugDoesNotProduceAllCompileRulesOnDisk() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    Flavor platformFlavor = ImmutableFlavor.of("iphonesimulator-x86_64");

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp")
        .withAppendedFlavors(AppleDebugFormat.NONE.getFlavor());
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:DemoAppBinary")
        .withAppendedFlavors(platformFlavor, AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.NONE.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);

    Path binaryOutput = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(target.getShortName() + ".app")
            .resolve(target.getShortName()));

    Path delegateFileOutput = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                binaryTarget.withFlavors(
                    platformFlavor,
                    ImmutableFlavor.of("compile-" + sanitize("AppDelegate.m.o")),
                    AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve("AppDelegate.m.o"));

    Path mainFileOutput = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                binaryTarget.withFlavors(
                    platformFlavor,
                    ImmutableFlavor.of("compile-" + sanitize("main.m.o")),
                    AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve("main.m.o"));

    assertThat(Files.exists(binaryOutput), equalTo(true));
    assertThat(Files.exists(delegateFileOutput), equalTo(false));
    assertThat(Files.exists(mainFileOutput), equalTo(false));
  }

  @Test
  public void testBuildingWithDwarfAndDsymDoesNotProduceAllCompileRulesOnDisk() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    Flavor platformFlavor = ImmutableFlavor.of("iphonesimulator-x86_64");

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp")
        .withAppendedFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor());
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:DemoAppBinary")
        .withAppendedFlavors(platformFlavor, AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);

    Path binaryOutput = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(target.getShortName() + ".app")
            .resolve(target.getShortName()));

    Path delegateFileOutput = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                binaryTarget.withFlavors(
                    platformFlavor,
                    ImmutableFlavor.of("compile-" + sanitize("AppDelegate.m.o")),
                    AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve("AppDelegate.m.o"));

    Path mainFileOutput = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                binaryTarget.withFlavors(
                    platformFlavor,
                    ImmutableFlavor.of("compile-" + sanitize("main.m.o")),
                    AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve("main.m.o"));

    assertThat(Files.exists(binaryOutput), equalTo(true));
    assertThat(Files.exists(delegateFileOutput), equalTo(false));
    assertThat(Files.exists(mainFileOutput), equalTo(false));
  }

  @Test
  public void testFlavoredAppleBundleBuildsAndDsymFileIsNotCreatedAndBinaryIsStripped()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format_for_binaries=dwarf_and_dsym",
        target.getFullyQualifiedName())
        .assertSuccess();
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(filesystem, target, "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        equalTo(false));
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        filesystem,
                        target.withFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor()),
                        "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        equalTo(false));
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(filesystem, target.withFlavors(), "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        equalTo(false));

    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.NONE.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path binaryOutput = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(target.getShortName() + ".app")
            .resolve(target.getShortName()));
    assertThat(Files.exists(binaryOutput), equalTo(true));

    ProcessExecutor.Result hasSymbol = workspace.runCommand("nm", binaryOutput.toString());
    String stdout = hasSymbol.getStdout().orElse("");
    assertThat(stdout, Matchers.not(containsString("t -[AppDelegate window]")));
    assertThat(stdout, containsString("U _UIApplicationMain"));
  }

  @Test
  public void testAppleBundleDebugFormatRespectsDefaultConfigSettingDSYM() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp");
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format_for_binaries=dwarf_and_dsym",
        target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path dwarfPath = workspace.getPath(
        BuildTargets.getGenPath(filesystem, appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app.dSYM")
            .resolve("Contents/Resources/DWARF")
            .resolve(appTarget.getShortName()));
    assertThat(Files.exists(dwarfPath), equalTo(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, dwarfPath);
  }

  @Test
  public void testAppleBundleDebugFormatRespectsDefaultConfigSettingNoDebug() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp");
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format_for_binaries=none",
        target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget = target.withFlavors(AppleDebugFormat.NONE.getFlavor());
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(filesystem, appTarget, "%s")
                    .resolve(appTarget.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(appTarget.getShortName()))),
        equalTo(false));
  }

  @Test
  public void multiarchBinaryShouldCopyLinkMapOfComponents()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    BuildTarget singleArchI386Target =
        BuildTargetFactory.newInstance("//:DemoApp#iphonesimulator-i386")
            .withAppendedFlavors(LinkerMapMode.LINKER_MAP.getFlavor());
    BuildTarget singleArchX8664Target =
        BuildTargetFactory.newInstance("//:DemoApp#iphonesimulator-x86_64")
            .withAppendedFlavors(LinkerMapMode.LINKER_MAP.getFlavor());
    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoApp#iphonesimulator-i386,iphonesimulator-x86_64")
            .withAppendedFlavors(LinkerMapMode.LINKER_MAP.getFlavor());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "multiarch_binary_linkmap", tmp);
    workspace.setUp();
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    assertTrue(
        "Has link map for i386 arch.",
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(filesystem, target, "%s-LinkMap")
                    .resolve(
                        singleArchI386Target
                            .withAppendedFlavors(
                                CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR,
                                LinkerMapMode.LINKER_MAP.getFlavor())
                            .getShortNameAndFlavorPostfix() + "-LinkMap.txt"))));
    assertTrue(
        "Has link map for x86_64 arch.",
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(filesystem, target, "%s-LinkMap")
                    .resolve(
                        singleArchX8664Target
                            .withAppendedFlavors(
                                CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR,
                                LinkerMapMode.LINKER_MAP.getFlavor())
                            .getShortNameAndFlavorPostfix() + "-LinkMap.txt"))));
  }

  @Test
  public void testBuildEmptySourceAppleBinaryDependsOnNonEmptyAppleLibrary() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "empty_source_targets",
        tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:real-none2#macosx-x86_64");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "run",
        target.getFullyQualifiedName());
    result.assertSuccess();
    Assert.assertThat(
        result.getStdout(),
        equalTo("Hello"));
  }

  private static void assertIsSymbolicLink(
      Path link,
      Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link));
    assertEquals(
        target,
        Files.readSymbolicLink(link));
  }
}
