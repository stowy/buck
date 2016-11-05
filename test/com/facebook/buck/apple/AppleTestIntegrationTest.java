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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class AppleTestIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testAppleTestHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_header_symlink_tree", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:Test#iphonesimulator-x86_64,private-headers");
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
        outputPath.resolve("Test/Header.h"),
        inputPath.resolve("Header.h"));
  }

  @Test
  public void testInfoPlistFromExportRule() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_info_plist_export_file", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo#iphonesimulator-x86_64");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = Paths.get(tmp.getRoot().toFile().getCanonicalPath());

    BuildTarget appleTestBundleFlavoredBuildTarget = buildTarget
        .withFlavors(
            ImmutableFlavor.of("iphonesimulator-x86_64"),
            ImmutableFlavor.of("apple-test-bundle"),
            AppleDebugFormat.DWARF.getFlavor(),
            LinkerMapMode.NO_LINKER_MAP.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(
            filesystem,
            appleTestBundleFlavoredBuildTarget,
            "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path infoPlistPath = bundlePath.resolve("Info.plist");

    assertTrue(Files.isDirectory(bundlePath));
    assertTrue(Files.isRegularFile(infoPlistPath));
  }

  @Test
  public void testSetsFrameworkSearchPathAndLinksCorrectly() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_framework_search_path", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo#iphonesimulator-x86_64");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = Paths.get(tmp.getRoot().toFile().getCanonicalPath());

    BuildTarget appleTestBundleFlavoredBuildTarget = buildTarget
        .withFlavors(
            ImmutableFlavor.of("iphonesimulator-x86_64"),
            ImmutableFlavor.of("apple-test-bundle"),
            AppleDebugFormat.DWARF.getFlavor(),
            LinkerMapMode.NO_LINKER_MAP.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(
            filesystem,
            appleTestBundleFlavoredBuildTarget,
            "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path testBinaryPath = bundlePath.resolve("foo");

    assertTrue(Files.isDirectory(bundlePath));
    assertTrue(Files.isRegularFile(testBinaryPath));
  }

  @Test
  public void testInfoPlistVariableSubstitutionWorksCorrectly() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_info_plist_substitution", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:foo#iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("foo_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDebugFormat.DWARF.getFlavor())
                .addFlavors(AppleTestDescription.BUNDLE_FLAVOR)
                .addFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor())
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));
  }

  @Test
  public void testDefaultPlatformBuilds() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_default_platform", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:foo");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("foo_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleTestDescription.BUNDLE_FLAVOR)
                .addFlavors(AppleDebugFormat.DWARF.getFlavor())
                .addFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor())
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));
  }

  @Test
  public void testLinkedAsMachOBundleWithNoDylibDeps() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_deps", tmp);
    workspace.setUp();

    BuildTarget buildTarget = workspace.newBuildTarget("//:foo");
    workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("foo_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(buildTarget)
                .addFlavors(AppleDebugFormat.DWARF.getFlavor())
                .addFlavors(AppleTestDescription.BUNDLE_FLAVOR)
                .addFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor())
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path projectRoot = Paths.get(tmp.getRoot().toFile().getCanonicalPath());
    BuildTarget appleTestBundleFlavoredBuildTarget = buildTarget
        .withAppendedFlavors(
            ImmutableFlavor.of("apple-test-bundle"),
            AppleDebugFormat.DWARF.getFlavor(),
            LinkerMapMode.NO_LINKER_MAP.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(
            filesystem,
            appleTestBundleFlavoredBuildTarget,
            "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path testBinaryPath = bundlePath.resolve("foo");

    ProcessExecutor.Result binaryFileTypeResult = workspace.runCommand(
        "file", "-b", testBinaryPath.toString());
    assertEquals(0, binaryFileTypeResult.getExitCode());
    assertThat(
        binaryFileTypeResult.getStdout().orElse(""),
        containsString("Mach-O 64-bit bundle x86_64"));

    ProcessExecutor.Result otoolResult = workspace.runCommand(
        "otool", "-L", testBinaryPath.toString());
    assertEquals(0, otoolResult.getExitCode());
    assertThat(
        otoolResult.getStdout().orElse(""),
        containsString("foo"));
    assertThat(
        otoolResult.getStdout().orElse(""),
        not(containsString("bar.dylib")));

    ProcessExecutor.Result nmResult = workspace.runCommand(
        "nm", "-j", testBinaryPath.toString());
    assertEquals(0, nmResult.getExitCode());
    assertThat(
        nmResult.getStdout().orElse(""),
        containsString("_OBJC_CLASS_$_Foo"));
    assertThat(
        nmResult.getStdout().orElse(""),
        containsString("_OBJC_CLASS_$_Bar"));
  }

  @Test
  public void testWithResourcesCopiesResourceFilesAndDirs() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_resources", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:foo#iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("foo_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDebugFormat.DWARF.getFlavor())
                .addFlavors(AppleTestDescription.BUNDLE_FLAVOR)
                .addFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor())
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));
  }

  @Test
  public void shouldRefuseToRunAppleTestIfXctestNotPresent() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest", tmp);
    workspace.setUp();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString(
        "Set xctool_path = /path/to/xctool or xctool_zip_target = //path/to:xctool-zip in the " +
        "[apple] section of .buckconfig to run this test"));
    workspace.runBuckCommand("test", "//:foo");
  }

  @Test
  public void successOnTestPassing() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = fbxctest/bin/fbxctest\n",
         ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:foo");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
  }

  @Test
  public void skipsXCUITests() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xcuitest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.writeContentsToPath(
        "[apple]\n  xctool_path = fbxctest/bin/fbxctest\n",
        ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:foo", "//:bar");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString(
            "NOTESTS <100ms  0 Passed   0 Skipped   0 Failed   XCUITest runs not supported"
        ));
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
  }

  @Test
  public void slowTestShouldFailWithTimeout() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "slow_xc_tests_per_rule_timeout", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.writeContentsToPath(
        "[apple]\n  xctool_path = fbxctest/bin/fbxctest\n",
        ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:spinning");
    result.assertSpecialExitCode("test should fail", 42);
    assertThat(
        result.getStderr(),
        containsString("Timed out after 100 ms running test command"));
  }


  @Test
  public void exitCodeIsCorrectOnTestFailure() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest_failure", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = fbxctest/bin/fbxctest\n",
         ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:foo");
    result.assertSpecialExitCode("test should fail", 42);
    assertThat(
        result.getStderr(),
        containsString("0 Passed   0 Skipped   1 Failed   FooXCTest"));
    assertThat(
        result.getStderr(),
        containsString("FAILURE FooXCTest -[FooXCTest testTwoPlusTwoEqualsFive]: FooXCTest.m:9"));
  }

  @Test
  public void successOnAppTestPassing() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_host_app", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = fbxctest/bin/fbxctest\n",
         ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:AppTest");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test
  public void testWithHostAppWithDsym() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_host_app", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.writeContentsToPath(
        "[apple]\n  xctool_path = fbxctest/bin/fbxctest\n",
        ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "//:AppTest",
        "--config",
        "cxx.cflags=-g",
        "--config",
        "apple.default_debug_info_format_for_binaries=DWARF_AND_DSYM",
        "--config",
        "apple.default_debug_info_format_for_libraries=DWARF_AND_DSYM",
        "--config",
        "apple.default_debug_info_format_for_tests=DWARF_AND_DSYM");
    result.assertSuccess();

    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   AppTest"));

    Path appTestDsym = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("AppTest#apple-test-bundle,dwarf-and-dsym,no-include-frameworks,no-linkermap")
        .resolve("AppTest.xctest.dSYM");
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol(
        "-[AppTest testMagicValue]",
        workspace,
        appTestDsym);

    Path hostAppDsym = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("TestHostApp#dwarf-and-dsym,no-include-frameworks")
        .resolve("TestHostApp.app.dSYM");
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol(
        "-[TestHostApp magicValue]",
        workspace,
        hostAppDsym);
  }

  @Test
  public void exitCodeIsCorrectOnAppTestFailure() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_host_app_failure", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = fbxctest/bin/fbxctest\n",
         ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
        "//:AppTest");
    result.assertSpecialExitCode("test should fail", 42);
    assertThat(
        result.getStderr(),
        containsString("0 Passed   0 Skipped   1 Failed   AppTest"));
    assertThat(
        result.getStderr(),
        containsString("FAILURE AppTest -[AppTest testMagicValueShouldFail]: AppTest.m:13"));
  }

  @Test
  public void successOnOsxLogicTestPassing() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_osx_logic_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
        "//:LibTest");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   LibTest"));
  }

  @Test
  public void buckTestOnLibTargetRunsTestTarget() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_osx_logic_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
        "//:Lib");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   LibTest"));
  }

  @Test
  public void successForAppTestWithXib() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "app_bundle_with_compiled_resources", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = fbxctest/bin/fbxctest\n",
         ".buckconfig.local");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:AppTest");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test
  public void successOnTestPassingWithFbXcTestZipTarget() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_fbxctest_zip_target", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
        "//:foo");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
  }

  // This test is disabled since the movement from xctool to fbxctest
  @Test
  public void testDependenciesLinking() throws IOException, InterruptedException {
    assumeTrue(false);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_dependencies_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
        "//:App");
    result.assertSuccess();

    ProcessExecutor.Result hasSymbol = workspace.runCommand(
        "nm",
        workspace
            .getPath(
                BuildTargets.getGenPath(
                    filesystem,
                    workspace.newBuildTarget("#AppBinary#binary,iphonesimulator-x86_64"),
                    "AppBinary#apple-dsym,iphonesimulator-x86_64.dSYM"))
            .toString());

    assertThat(hasSymbol.getExitCode(), equalTo(0));
    assertThat(hasSymbol.getStdout().get(), containsString("U _OBJC_CLASS_$_Library"));
  }

  @Test
  public void environmentOverrideAffectsXctoolTest() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    // Our version of xctool doesn't pass through any environment variables, so just see if xctool
    // itself crashes.
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.runBuckCommand(
        "test",
        "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
        "//:foo")
        .assertSuccess("normally the test should succeed");
    workspace.resetBuildLogFile();
    workspace.runBuckCommand(
        "test",
        "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
        "--test-runner-env", "DYLD_INSERT_LIBRARIES=/non_existent_library_omg.dylib",
        "//:foo")
        .assertTestFailure("test should fail if i set incorrect dyld environment");
  }

  @Test
  public void environmentOverrideAffectsXctestTest() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_env", tmp);
    workspace.setUp();
    ProjectWorkspace.ProcessResult result;
    result = workspace.runBuckCommand(
        "test",
        "--config", "apple.xctest_platforms=macosx",
        "//:foo#macosx-x86_64");
    result.assertTestFailure("normally the test should fail");
    workspace.resetBuildLogFile();
    result = workspace.runBuckCommand(
        "test",
        "--config", "apple.xctest_platforms=macosx",
        "--test-runner-env", "FOO=bar",
        "//:foo#macosx-x86_64");
    result.assertSuccess("should pass when I pass correct environment");
  }

  @Test
  public void appleTestWithoutTestHostShouldSupportMultiarch() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    BuildTarget target = BuildTargetFactory.newInstance(
        "//:foo#iphonesimulator-i386,iphonesimulator-x86_64");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
            target.getFullyQualifiedName());
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));

    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path output = workspace.getDestPath().resolve(
        Iterables.getLast(Splitter.on(' ').limit(2).split(result.getStdout().trim())));
    // check result is actually multiarch.
    ProcessExecutor.Result lipoVerifyResult = workspace.runCommand(
        "lipo", output.resolve("foo").toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(
        lipoVerifyResult.getStderr().orElse(""),
        0,
        lipoVerifyResult.getExitCode());
  }

  @Test
  public void appleTestWithoutTestHostMultiarchShouldHaveMultiarchDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    BuildTarget target = BuildTargetFactory.newInstance(
        "//:foo#iphonesimulator-i386,iphonesimulator-x86_64");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "--config", "cxx.cflags=-g",
            "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
            "--config", "apple.default_debug_info_format_for_binaries=DWARF_AND_DSYM",
            "--config", "apple.default_debug_info_format_for_libraries=DWARF_AND_DSYM",
            "--config", "apple.default_debug_info_format_for_tests=DWARF_AND_DSYM",
            target.getFullyQualifiedName());
    result.assertSuccess();

    BuildTarget libraryTarget =
        target.withAppendedFlavors(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR);
    Path output = workspace.getDestPath().resolve(
        BuildTargets.getGenPath(
            filesystem,
            libraryTarget.withAppendedFlavors(AppleDsym.RULE_FLAVOR),
            "%s.dSYM"))
        .resolve("Contents/Resources/DWARF/" + libraryTarget.getShortName());
    ProcessExecutor.Result lipoVerifyResult = workspace.runCommand(
        "lipo", output.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(
        lipoVerifyResult.getStderr().orElse(""),
        0,
        lipoVerifyResult.getExitCode());
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForConcreteArchitectures(
        "-[FooXCTest testTwoPlusTwoEqualsFour]",
        workspace,
        output,
        Optional.of(ImmutableList.of("i386", "x86_64")));
  }

  @Test
  public void appleTestWithTestHostShouldSupportMultiarch() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_host_app", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"),
        Paths.get("fbxctest"));
    BuildTarget target = BuildTargetFactory.newInstance(
        "//:AppTest#iphonesimulator-i386,iphonesimulator-x86_64");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "--config", "apple.xctool_path=fbxctest/bin/fbxctest",
            target.getFullyQualifiedName());
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   AppTest"));

    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path output = workspace.getDestPath().resolve(
        Iterables.getLast(Splitter.on(' ').limit(2).split(result.getStdout().trim())));
    // check result is actually multiarch.
    ProcessExecutor.Result lipoVerifyResult = workspace.runCommand(
        "lipo", output.resolve("AppTest").toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(
        lipoVerifyResult.getStderr().orElse(""),
        0,
        lipoVerifyResult.getExitCode());
  }

  private static void assertIsSymbolicLink(
      Path link,
      Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link));
    assertTrue(Files.isSameFile(target, Files.readSymbolicLink(link)));
  }
}
