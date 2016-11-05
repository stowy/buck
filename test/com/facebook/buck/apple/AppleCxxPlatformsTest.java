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

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDictionary;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.swift.SwiftPlatform;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public class AppleCxxPlatformsTest {

  private static final ImmutableSet<Path> COMMON_KNOWN_PATHS =  ImmutableSet.of(
      Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"),
      Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"),
      Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
      Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/lipo"),
      Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/ranlib"),
      Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/strip"),
      Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/nm"),
      Paths.get("usr/bin/actool"),
      Paths.get("usr/bin/ibtool"),
      Paths.get("usr/bin/momc"),
      Paths.get("usr/bin/lldb"),
      Paths.get("usr/bin/xctest"));

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public TestLogSink logSink = new TestLogSink(AppleCxxPlatforms.class);

  @Rule
  public TemporaryPaths temp = new TemporaryPaths();

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void iphoneOSSdkPathsBuiltFromDirectory() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONEOS)
        .setName("iphoneos8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/codesign_allocate"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.empty(),
            Optional.empty());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    assertEquals(
        ImmutableList.of("usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Toolchains/XcodeDefault.xctoolchain/usr/bin/codesign_allocate"),
        appleCxxPlatform.getCodesignAllocate().get().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));

    assertEquals(
        ImmutableFlavor.of("iphoneos8.0-armv7"),
        cxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot",
            Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk").toString()));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-arch", "armv7"));
    assertThat(
        cxxPlatform.getAsflags(),
        hasConsecutiveItems("-arch", "armv7"));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-mios-version-min=7.0"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasConsecutiveItems(
            "-Wl,-sdk_version",
            "-Wl,8.0"));
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar")
            .toString(),
        cxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }

  @Test
  public void watchOSSdkPathsBuiltFromDirectory() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/WatchOS.platform"))
            .setSdkPath(Paths.get("Platforms/WatchOS.platform/Developer/SDKs/WatchOS2.0.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.WATCHOS)
        .setName("watchos2.0")
        .setVersion("2.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/ar"))

        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "2.0",
            "armv7k",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.empty(),
            Optional.empty());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    assertEquals(
        ImmutableList.of("usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));

    assertEquals(
        ImmutableFlavor.of("watchos2.0-armv7k"),
        cxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot",
            Paths.get("Platforms/WatchOS.platform/Developer/SDKs/WatchOS2.0.sdk").toString()));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-arch", "armv7k"));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-mwatchos-version-min=2.0"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasConsecutiveItems(
            "-Wl,-sdk_version",
            "-Wl,2.0"));
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/WatchOS.platform/Developer/usr/bin/ar")
            .toString(),
        cxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }

  @Test
  public void appleTVOSSdkPathsBuiltFromDirectory() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/AppleTVOS.platform"))
            .setSdkPath(Paths.get("Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS9.1.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.APPLETVOS)
        .setName("appletvos9.1")
        .setVersion("9.1")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Platforms/AppleTVOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/AppleTVOS.platform/Developer/usr/bin/ar"))

        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "9.1",
            "arm64",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.empty(),
            Optional.empty());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    assertEquals(
        ImmutableList.of("usr/bin/actool"),
        appleCxxPlatform.getActool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/ibtool"),
        appleCxxPlatform.getIbtool().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("usr/bin/lldb"),
        appleCxxPlatform.getLldb().getCommandPrefix(resolver));
    assertEquals(
        ImmutableList.of("Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil"),
        appleCxxPlatform.getDsymutil().getCommandPrefix(resolver));

    assertEquals(
        ImmutableList.of("usr/bin/xctest"),
        appleCxxPlatform.getXctest().getCommandPrefix(resolver));

    assertEquals(
        ImmutableFlavor.of("appletvos9.1-arm64"),
        cxxPlatform.getFlavor());
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang").toString(),
        cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertThat(
        ImmutableList.<String>builder()
            .addAll(cxxPlatform.getCc().resolve(ruleResolver).getCommandPrefix(resolver))
            .addAll(cxxPlatform.getCflags())
            .build(),
        hasConsecutiveItems(
            "-isysroot",
            Paths.get("Platforms/AppleTVOS.platform/Developer/SDKs/AppleTVOS9.1.sdk").toString()));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-arch", "arm64"));
    assertThat(
        cxxPlatform.getCflags(),
        hasConsecutiveItems("-mtvos-version-min=9.1"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasConsecutiveItems(
            "-Wl,-sdk_version",
            "-Wl,9.1"));
    assertEquals(
        Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++").toString(),
        cxxPlatform.getCxx().resolve(ruleResolver).getCommandPrefix(resolver).get(0));
    assertEquals(
        Paths.get("Platforms/AppleTVOS.platform/Developer/usr/bin/ar")
            .toString(),
        cxxPlatform.getAr().getCommandPrefix(resolver).get(0));
  }

  @Test
  public void invalidFlavorCharactersInSdkAreEscaped() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONEOS)
        .setName("_(in)+va|id_")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "cha+rs",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.empty(),
            Optional.empty());

    assertEquals(
        ImmutableFlavor.of("__in__va_id_-cha_rs"),
        appleCxxPlatform.getCxxPlatform().getFlavor());
  }

  @Test
  public void cxxToolParamsReadFromBuckConfig() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONEOS)
        .setName("iphoneos8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "cxx", ImmutableMap.of(
                        "cflags", "-std=gnu11",
                        "cppflags", "-DCTHING",
                        "cxxflags", "-std=c++11",
                        "cxxppflags", "-DCXXTHING"))).build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.empty(),
            Optional.empty());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-std=gnu11"));
    assertThat(
        cxxPlatform.getCppflags(),
        hasItems("-DCTHING"));
    assertThat(
        cxxPlatform.getCxxflags(),
        hasItem("-std=c++11"));
    assertThat(
        cxxPlatform.getCxxppflags(),
        hasItems("-DCXXTHING"));
  }

  @Test
  public void pathNotFoundThrows() throws Exception {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("Cannot find tool"));
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("."))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONEOS)
        .setName("iphoneos8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatforms.buildWithExecutableChecker(
        targetSdk,
        "7.0",
        "armv7",
        appleSdkPaths,
        FakeBuckConfig.builder().build(),
        new FakeAppleConfig(),
        new FakeExecutableFinder(ImmutableSet.of()),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  public void iphoneOSSimulatorPlatformSetsLinkerFlags() throws Exception {
    AppleSdkPaths appleSdkPaths = AppleSdkPaths.builder()
        .setDeveloperPath(Paths.get("."))
        .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
        .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/ar"))
        .add(Paths.get("Tools/otest"))
        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
        .setName("iphonesimulator8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "7.0",
            "armv7",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.empty(),
            Optional.empty());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-mios-simulator-version-min=7.0"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasItem("-mios-simulator-version-min=7.0"));
  }

  @Test
  public void watchOSSimulatorPlatformSetsLinkerFlags() throws Exception {
    AppleSdkPaths appleSdkPaths = AppleSdkPaths.builder()
        .setDeveloperPath(Paths.get("."))
        .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setPlatformPath(Paths.get("Platforms/WatchSimulator.platform"))
        .setSdkPath(
            Paths.get("Platforms/WatchSimulator.platform/Developer/SDKs/WatchSimulator2.0.sdk")
        )
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneSimulator.platform/Developer/usr/bin/ar"))

        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.WATCHSIMULATOR)
        .setName("watchsimulator2.0")
        .setVersion("2.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "2.0",
            "armv7k",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.empty(),
            Optional.empty());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-mwatchos-simulator-version-min=2.0"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasItem("-mwatchos-simulator-version-min=2.0"));
  }

  @Test
  public void appleTVOSSimulatorPlatformSetsLinkerFlags() throws Exception {
    AppleSdkPaths appleSdkPaths = AppleSdkPaths.builder()
        .setDeveloperPath(Paths.get("."))
        .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setPlatformPath(Paths.get("Platforms/AppleTVSimulator.platform"))
        .setSdkPath(
            Paths.get("Platforms/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator9.1.sdk")
        )
        .build();

    ImmutableSet<Path> paths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Platforms/AppleTVSimulator.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/AppleTVSimulator.platform/Developer/usr/bin/ar"))

        .build();

    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();

    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.APPLETVSIMULATOR)
        .setName("appletvsimulator9.1")
        .setVersion("9.1")
        .setToolchains(ImmutableList.of(toolchain))
        .build();

    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithExecutableChecker(
            targetSdk,
            "9.1",
            "arm64",
            appleSdkPaths,
            FakeBuckConfig.builder().build(),
            new FakeAppleConfig(),
            new FakeExecutableFinder(paths),
            Optional.empty(),
            Optional.empty());

    CxxPlatform cxxPlatform = appleCxxPlatform.getCxxPlatform();

    assertThat(
        cxxPlatform.getCflags(),
        hasItem("-mtvos-simulator-version-min=9.1"));
    assertThat(
        cxxPlatform.getLdflags(),
        hasItem("-mtvos-simulator-version-min=9.1"));
  }

  enum Operation {
    PREPROCESS,
    COMPILE,
    PREPROCESS_AND_COMPILE,
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<Flavor, RuleKey> constructCompileRuleKeys(
      Operation operation,
      ImmutableMap<Flavor, AppleCxxPlatform> cxxPlatforms) {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    String source = "source.cpp";
    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            0,
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("source.cpp", Strings.repeat("a", 40))
                    .build()),
            pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    ImmutableMap.Builder<Flavor, RuleKey> ruleKeys =
        ImmutableMap.builder();
    for (Map.Entry<Flavor, AppleCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactory.builder()
          .setParams(new FakeBuildRuleParamsBuilder(target).build())
          .setResolver(resolver)
          .setPathResolver(pathResolver)
          .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
          .setCxxPlatform(entry.getValue().getCxxPlatform())
          .setPicType(CxxSourceRuleFactory.PicType.PIC)
          .build();
      CxxPreprocessAndCompile rule;
      switch (operation) {
        case PREPROCESS_AND_COMPILE:
          rule =
              cxxSourceRuleFactory.createPreprocessAndCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new FakeSourcePath(source),
                      ImmutableList.of()),
                  CxxPreprocessMode.COMBINED);
          break;
        case PREPROCESS:
          rule =
              cxxSourceRuleFactory.createPreprocessBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX,
                      new FakeSourcePath(source),
                      ImmutableList.of()));
          break;
        case COMPILE:
          rule =
              cxxSourceRuleFactory.createCompileBuildRule(
                  source,
                  CxxSource.of(
                      CxxSource.Type.CXX_CPP_OUTPUT,
                      new FakeSourcePath(source),
                      ImmutableList.of()));
          break;
        default:
          throw new IllegalStateException();
      }
      ruleKeys.put(entry.getKey(), ruleKeyBuilderFactory.build(rule));
    }
    return ruleKeys.build();
  }

  // Create and return some rule keys from a dummy source for the given platforms.
  private ImmutableMap<Flavor, RuleKey> constructLinkRuleKeys(
      ImmutableMap<Flavor, AppleCxxPlatform> cxxPlatforms) throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            0,
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("input.o", Strings.repeat("a", 40))
                    .build()),
            pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    ImmutableMap.Builder<Flavor, RuleKey> ruleKeys =
        ImmutableMap.builder();
    for (Map.Entry<Flavor, AppleCxxPlatform> entry : cxxPlatforms.entrySet()) {
      BuildRule rule =
          CxxLinkableEnhancer.createCxxLinkableBuildRule(
              CxxPlatformUtils.DEFAULT_CONFIG,
              entry.getValue().getCxxPlatform(),
              new FakeBuildRuleParamsBuilder(target).build(),
              resolver,
              pathResolver,
              target,
              Linker.LinkType.EXECUTABLE,
              Optional.empty(),
              Paths.get("output"),
              Linker.LinkableDepType.SHARED,
              ImmutableList.of(),
              Optional.empty(),
              Optional.empty(),
              ImmutableSet.of(),
              NativeLinkableInput.builder()
                  .setArgs(SourcePathArg.from(pathResolver, new FakeSourcePath("input.o")))
                  .build());
      ruleKeys.put(entry.getKey(), ruleKeyBuilderFactory.build(rule));
    }
    return ruleKeys.build();
  }

  private AppleCxxPlatform buildAppleCxxPlatform(Path root) {
    AppleSdkPaths appleSdkPaths = AppleSdkPaths.builder()
        .setDeveloperPath(root)
        .addToolchainPaths(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
        .setPlatformPath(root.resolve("Platforms/iPhoneOS.platform"))
        .setSdkPath(
            root.resolve("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
        .build();
    AppleToolchain toolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(root.resolve("Toolchains/XcodeDefault.xctoolchain"))
        .setVersion("1")
        .build();
    AppleSdk targetSdk = AppleSdk.builder()
        .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
        .setName("iphonesimulator8.0")
        .setVersion("8.0")
        .setToolchains(ImmutableList.of(toolchain))
        .build();
    return AppleCxxPlatforms.buildWithExecutableChecker(
        targetSdk,
        "7.0",
        "armv7",
        appleSdkPaths,
        FakeBuckConfig.builder().build(),
        new FakeAppleConfig(),
        new AlwaysFoundExecutableFinder(),
        Optional.empty(),
        Optional.empty());
  }

  // The important aspects we check for in rule keys is that the host platform and the path
  // to the NDK don't cause changes.
  @Test
  public void checkRootAndPlatformDoNotAffectRuleKeys() throws Exception {
    Map<String, ImmutableMap<Flavor, RuleKey>> preprocessAndCompileRukeKeys = Maps.newHashMap();
    Map<String, ImmutableMap<Flavor, RuleKey>> preprocessRukeKeys = Maps.newHashMap();
    Map<String, ImmutableMap<Flavor, RuleKey>> compileRukeKeys = Maps.newHashMap();
    Map<String, ImmutableMap<Flavor, RuleKey>> linkRukeKeys = Maps.newHashMap();

    // Iterate building up rule keys for combinations of different platforms and NDK root
    // directories.
    for (String dir : ImmutableList.of("something", "something else")) {
      AppleCxxPlatform platform = buildAppleCxxPlatform(Paths.get(dir));
      preprocessAndCompileRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructCompileRuleKeys(
              Operation.PREPROCESS_AND_COMPILE,
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
      preprocessRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructCompileRuleKeys(
              Operation.PREPROCESS,
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
      compileRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructCompileRuleKeys(
              Operation.COMPILE,
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
      linkRukeKeys.put(
          String.format("AppleCxxPlatform(%s)", dir),
          constructLinkRuleKeys(
              ImmutableMap.of(platform.getCxxPlatform().getFlavor(), platform)));
    }

    // If everything worked, we should be able to collapse all the generated rule keys down
    // to a singleton set.
    assertThat(
        Arrays.toString(preprocessAndCompileRukeKeys.entrySet().toArray()),
        Sets.newHashSet(preprocessAndCompileRukeKeys.values()),
        Matchers.hasSize(1));
    assertThat(
        Arrays.toString(preprocessRukeKeys.entrySet().toArray()),
        Sets.newHashSet(preprocessRukeKeys.values()),
        Matchers.hasSize(1));
    assertThat(
        Arrays.toString(compileRukeKeys.entrySet().toArray()),
        Sets.newHashSet(compileRukeKeys.values()),
        Matchers.hasSize(1));
    assertThat(
        Arrays.toString(linkRukeKeys.entrySet().toArray()),
        Sets.newHashSet(linkRukeKeys.values()),
        Matchers.hasSize(1));

  }

  @Test
  public void nonExistentPlatformVersionPlistIsLogged() {
    AppleCxxPlatform platform = buildAppleCxxPlatform(Paths.get("/nonexistentjabberwock"));
    assertThat(platform.getBuildVersion(), equalTo(Optional.empty()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern(".*does not exist.*Build version will be unset.*"))));
  }

  @Test
  public void invalidPlatformVersionPlistIsLogged() throws Exception {
    Path tempRoot = temp.getRoot();
    Path platformRoot = tempRoot.resolve("Platforms/iPhoneOS.platform");
    Files.createDirectories(platformRoot);
    Files.write(
        platformRoot.resolve("version.plist"),
        "I am, as a matter of fact, an extremely invalid plist.".getBytes(Charsets.UTF_8));
    AppleCxxPlatform platform = buildAppleCxxPlatform(tempRoot);
    assertThat(platform.getBuildVersion(), equalTo(Optional.empty()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern("Failed to parse.*Build version will be unset.*"))));
  }

  @Test
  public void platformVersionPlistWithMissingFieldIsLogged() throws Exception {
    Path tempRoot = temp.getRoot();
    Path platformRoot = tempRoot.resolve("Platforms/iPhoneOS.platform");
    Files.createDirectories(platformRoot);
    Files.write(
        platformRoot.resolve("version.plist"),
        new NSDictionary().toXMLPropertyList().getBytes(Charsets.UTF_8));
    AppleCxxPlatform platform = buildAppleCxxPlatform(tempRoot);
    assertThat(platform.getBuildVersion(), equalTo(Optional.empty()));
    assertThat(
        logSink.getRecords(),
        hasItem(
            TestLogSink.logRecordWithMessage(
                matchesPattern(".*missing ProductBuildVersion. Build version will be unset.*"))));
  }

  @Test
  public void appleCxxPlatformWhenNoSwiftToolchainPreferredShouldUseDefaultSwift()
      throws IOException {
    AppleCxxPlatform platformWithDefaultSwift = buildAppleCxxPlatformWithSwiftToolchain(true);
    Optional<SwiftPlatform> swiftPlatformOptional = platformWithDefaultSwift.getSwiftPlatform();
    assertThat(swiftPlatformOptional.isPresent(), is(true));
    Tool swiftTool = swiftPlatformOptional.get().getSwift();
    assertTrue(swiftTool instanceof VersionedTool);
    assertThat(((VersionedTool) swiftTool).getPath(),
        equalTo(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/swift")));

    assertThat(swiftPlatformOptional.get().getSwiftRuntimePaths(), Matchers.empty());
  }

  @Test
  public void appleCxxPlatformShouldUsePreferredSwiftVersion() throws IOException {
    AppleCxxPlatform platformWithConfiguredSwift = buildAppleCxxPlatformWithSwiftToolchain(false);
    Optional<SwiftPlatform> swiftPlatformOptional = platformWithConfiguredSwift.getSwiftPlatform();
    assertThat(swiftPlatformOptional.isPresent(), is(true));
    Tool swiftTool = swiftPlatformOptional.get().getSwift();
    assertThat(((VersionedTool) swiftTool).getPath(),
        not(equalTo(Paths.get("Toolchains/Swift_2.3.xctoolchain/usr/bin/swift"))));

    assertThat(swiftPlatformOptional.get().getSwiftRuntimePaths(),
        equalTo(ImmutableSet.of(temp.getRoot().resolve("usr/lib/swift/iphoneos"))));
  }

  @Test
  public void checkSwiftPlatformUsesCorrectMinTargetSdk() throws IOException {
    AppleCxxPlatform platformWithConfiguredSwift = buildAppleCxxPlatformWithSwiftToolchain(true);
    Tool swift = platformWithConfiguredSwift.getSwiftPlatform().get().getSwift();
    assertThat(swift, notNullValue());
    assertThat(swift, instanceOf(VersionedTool.class));
    VersionedTool versionedSwift = (VersionedTool) swift;
    assertThat(versionedSwift.getExtraArgs(), hasItem("i386-apple-ios7.0"));
  }

  private AppleCxxPlatform buildAppleCxxPlatformWithSwiftToolchain(boolean useDefaultSwift)
      throws IOException {
    Path tempRoot = temp.getRoot();
    AppleToolchain swiftToolchain = AppleToolchain.builder()
        .setIdentifier("com.apple.dt.XcodeDefault")
        .setPath(tempRoot)
        .setVersion("1")
        .build();
    temp.newFolder("usr", "bin");
    temp.newFolder("usr", "lib", "swift", "iphoneos");
    temp.newFolder("usr", "lib", "swift_static", "iphoneos");
    MoreFiles.makeExecutable(temp.newFile("usr/bin/swift"));
    MoreFiles.makeExecutable(temp.newFile("usr/bin/swift-stdlib-tool"));
    Optional<AppleToolchain> selectedSwiftToolChain = useDefaultSwift ?
        Optional.empty() : Optional.of(swiftToolchain);
    final ImmutableSet<Path> knownPaths = ImmutableSet.<Path>builder()
        .addAll(COMMON_KNOWN_PATHS)
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"))
        .add(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/swift"))
        .add(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/swift-stdlib-tool"))
        .build();
    return AppleCxxPlatforms.buildWithExecutableChecker(
        FakeAppleRuleDescriptions.DEFAULT_IPHONEOS_SDK,
        "7.0",
        "i386",
        FakeAppleRuleDescriptions.DEFAULT_IPHONEOS_SDK_PATHS,
        FakeBuckConfig.builder().build(),
        new FakeAppleConfig(),
        new ExecutableFinder() {
          @Override
          public Optional<Path> getOptionalExecutable(
              Path suggestedPath,
              ImmutableCollection<Path> searchPath,
              ImmutableCollection<String> fileSuffixes) {
            Optional<Path> realPath = super.getOptionalExecutable(
                suggestedPath,
                searchPath,
                fileSuffixes);
            if (realPath.isPresent()) {
              return realPath;
            }
            for (Path path : knownPaths) {
              if (suggestedPath.equals(path.getFileName())) {
                return Optional.of(path);
              }
            }
            return Optional.empty();
          }
        },
        Optional.of(FakeAppleRuleDescriptions.PROCESS_EXECUTOR),
        selectedSwiftToolChain);
  }
}
