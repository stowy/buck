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

package com.facebook.buck.cxx;

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public class CxxLinkableEnhancerTest {

  private static final Path DEFAULT_OUTPUT = Paths.get("libblah.a");
  private static final ImmutableList<Arg> DEFAULT_INPUTS =
      SourcePathArg.from(
          new SourcePathResolver(
              new BuildRuleResolver(
                  TargetGraph.EMPTY,
                  new DefaultTargetNodeToBuildRuleTransformer())),
          new FakeSourcePath("a.o"),
          new FakeSourcePath("b.o"),
          new FakeSourcePath("c.o"));
  private static final ImmutableSortedSet<NativeLinkable> EMPTY_DEPS = ImmutableSortedSet.of();
  private static final CxxPlatform CXX_PLATFORM = CxxPlatformUtils.build(
      new CxxBuckConfig(FakeBuckConfig.builder().build()));

  private static class FakeNativeLinkable extends FakeBuildRule implements NativeLinkable {

    private final NativeLinkableInput staticInput;
    private final NativeLinkableInput sharedInput;

    public FakeNativeLinkable(
        BuildRuleParams params,
        SourcePathResolver resolver,
        NativeLinkableInput staticInput,
        NativeLinkableInput sharedInput) {
      super(params, resolver);
      this.staticInput = Preconditions.checkNotNull(staticInput);
      this.sharedInput = Preconditions.checkNotNull(sharedInput);
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
      return FluentIterable.from(getDeclaredDeps())
          .filter(NativeLinkable.class);
    }

    @Override
    public Iterable<NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
      return FluentIterable.from(getDeclaredDeps())
          .filter(NativeLinkable.class);
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType type) {
      return type == Linker.LinkableDepType.STATIC ? staticInput : sharedInput;
    }

    @Override
    public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      return Linkage.ANY;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(
        CxxPlatform cxxPlatform) {
      return ImmutableMap.of();
    }

  }

  private static FakeNativeLinkable createNativeLinkable(
      String target,
      SourcePathResolver resolver,
      NativeLinkableInput staticNativeLinkableInput,
      NativeLinkableInput sharedNativeLinkableInput,
      BuildRule... deps) {
    return new FakeNativeLinkable(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver,
        staticNativeLinkableInput,
        sharedNativeLinkableInput);
  }

  @Test
  public void testThatBuildTargetSourcePathDepsAndPathsArePropagated() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    // Create a couple of genrules to generate inputs for an archive rule.
    Genrule genrule1 = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
        .setOut("foo/bar.o")
        .build(resolver);
    Genrule genrule2 = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule2"))
        .setOut("foo/test.o")
        .build(resolver);

    // Build the archive using a normal input the outputs of the genrules above.
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        resolver,
        new SourcePathResolver(resolver),
        target,
        Linker.LinkType.EXECUTABLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        EMPTY_DEPS,
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(SourcePathArg.from(
                new SourcePathResolver(resolver),
                new FakeSourcePath("simple.o"),
                new BuildTargetSourcePath(genrule1.getBuildTarget()),
                new BuildTargetSourcePath(genrule2.getBuildTarget())))
            .build());

    // Verify that the archive dependencies include the genrules providing the
    // SourcePath inputs.
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule1, genrule2),
        cxxLink.getDeps());
  }

  @Test
  public void testThatOriginalBuildParamsDepsDoNotPropagateToArchive() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    // Create an `Archive` rule using build params with an existing dependency,
    // as if coming from a `TargetNode` which had declared deps.  These should *not*
    // propagate to the `Archive` rule, since it only cares about dependencies generating
    // it's immediate inputs.
    BuildRule dep = new FakeBuildRule(
        new FakeBuildRuleParamsBuilder("//:fake").build(),
        pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:archive")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dummy"))
            .setDeclaredDeps(ImmutableSortedSet.of(dep))
            .build();
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        ruleResolver,
        pathResolver,
        target,
        Linker.LinkType.EXECUTABLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        EMPTY_DEPS,
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(DEFAULT_INPUTS)
            .build());

    // Verify that the archive rules dependencies are empty.
    assertEquals(cxxLink.getDeps(), ImmutableSortedSet.<BuildRule>of());
  }

  @Test
  public void testThatBuildTargetsFromNativeLinkableDepsContributeToActualDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    // Create a dummy build rule and add it to the resolver.
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//:fake");
    FakeBuildRule fakeBuildRule = new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(fakeBuildTarget).build(), pathResolver);
    resolver.addToIndex(fakeBuildRule);

    // Create a native linkable dep and have it list the fake build rule above as a link
    // time dependency.
    NativeLinkableInput nativeLinkableInput = NativeLinkableInput.of(
        ImmutableList.of(
            new SourcePathArg(
                pathResolver,
                new BuildTargetSourcePath(fakeBuildRule.getBuildTarget()))),
        ImmutableSet.of(),
        ImmutableSet.of());
    FakeNativeLinkable nativeLinkable = createNativeLinkable(
        "//:dep",
        pathResolver,
        nativeLinkableInput,
        nativeLinkableInput);

    // Construct a CxxLink object and pass the native linkable above as the dep.
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        resolver,
        pathResolver,
        target,
        Linker.LinkType.EXECUTABLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        ImmutableList.<NativeLinkable>of(nativeLinkable),
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(DEFAULT_INPUTS)
            .build());

    // Verify that the fake build rule made it in as a dep.
    assertTrue(cxxLink.getDeps().contains(fakeBuildRule));
  }

  @Test
  public void createCxxLinkableBuildRuleExecutableVsShared() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    String soname = "soname";
    ImmutableList<String> sonameArgs =
        ImmutableList.copyOf(CXX_PLATFORM.getLd().resolve(ruleResolver).soname(soname));

    // Construct a CxxLink object which links as an executable.
    CxxLink executable = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        ruleResolver,
        pathResolver,
        target,
        Linker.LinkType.EXECUTABLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        EMPTY_DEPS,
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(DEFAULT_INPUTS)
            .build());
    assertFalse(executable.getArgs().contains(new StringArg("-shared")));
    assertEquals(Collections.indexOfSubList(executable.getArgs(), sonameArgs), -1);

    // Construct a CxxLink object which links as a shared lib.
    CxxLink shared = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        ruleResolver,
        pathResolver,
        target,
        Linker.LinkType.SHARED,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        EMPTY_DEPS,
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(DEFAULT_INPUTS)
            .build());
    assertTrue(Arg.stringify(shared.getArgs()).contains("-shared"));
    assertEquals(Collections.indexOfSubList(shared.getArgs(), sonameArgs), -1);

    // Construct a CxxLink object which links as a shared lib with a SONAME.
    CxxLink sharedWithSoname = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        ruleResolver,
        pathResolver,
        target,
        Linker.LinkType.SHARED,
        Optional.of("soname"),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        EMPTY_DEPS,
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(DEFAULT_INPUTS)
            .build());
    ImmutableList<String> args = Arg.stringify(sharedWithSoname.getArgs());
    assertTrue(args.contains("-shared"));
    assertNotEquals(Collections.indexOfSubList(args, sonameArgs), -1);
  }

  @Test
  public void createCxxLinkableBuildRuleStaticVsSharedDeps() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    // Create a native linkable dep and have it list the fake build rule above as a link
    // time dependency
    String staticArg = "static";
    NativeLinkableInput staticInput = NativeLinkableInput.of(
        ImmutableList.of(new StringArg(staticArg)),
        ImmutableSet.of(),
        ImmutableSet.of());
    String sharedArg = "shared";
    NativeLinkableInput sharedInput = NativeLinkableInput.of(
        ImmutableList.of(new StringArg(sharedArg)),
        ImmutableSet.of(),
        ImmutableSet.of());
    FakeNativeLinkable nativeLinkable = createNativeLinkable("//:dep",
        pathResolver,
        staticInput, sharedInput);

    // Construct a CxxLink object which links using static dependencies.
    CxxLink staticLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        ruleResolver,
        pathResolver,
        target,
        Linker.LinkType.EXECUTABLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        ImmutableList.<NativeLinkable>of(nativeLinkable),
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(DEFAULT_INPUTS)
            .build());
    ImmutableList<String> args = Arg.stringify(staticLink.getArgs());
    assertTrue(args.contains(staticArg) ||
        args.contains("-Wl," + staticArg));
    assertFalse(args.contains(sharedArg));
    assertFalse(args.contains("-Wl," + sharedArg));

    // Construct a CxxLink object which links using shared dependencies.
    CxxLink sharedLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        ruleResolver,
        pathResolver,
        target,
        Linker.LinkType.EXECUTABLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.SHARED,
        ImmutableList.<NativeLinkable>of(nativeLinkable),
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(DEFAULT_INPUTS)
            .build());
    args = Arg.stringify(sharedLink.getArgs());
    assertFalse(args.contains(staticArg));
    assertFalse(args.contains("-Wl," + staticArg));
    assertTrue(
        args.contains(sharedArg) ||
            args.contains("-Wl," + sharedArg));
  }

  @Test
  public void platformLdFlags() throws Exception {
    ImmutableMap<Linker.LinkableDepType, String> runtimes =
        ImmutableMap.of(
            Linker.LinkableDepType.SHARED, "-ldummy-shared-libc",
            Linker.LinkableDepType.STATIC, "-ldummy-static-libc",
            Linker.LinkableDepType.STATIC_PIC, "-ldummy-static-pic-libc");
    CxxPlatform cxxPlatform =
        CxxPlatform.builder()
            .from(CXX_PLATFORM)
            .putAllRuntimeLdflags(runtimes.asMultimap())
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    for (Map.Entry<Linker.LinkableDepType, String> ent : runtimes.entrySet()) {
      CxxLink lib =
          CxxLinkableEnhancer.createCxxLinkableBuildRule(
              CxxPlatformUtils.DEFAULT_CONFIG,
              cxxPlatform,
              params,
              ruleResolver,
              pathResolver,
              target,
              Linker.LinkType.SHARED,
              Optional.empty(),
              DEFAULT_OUTPUT,
              ent.getKey(),
              EMPTY_DEPS,
              Optional.empty(),
              Optional.empty(),
              ImmutableSet.of(),
              NativeLinkableInput.builder()
                  .setArgs(DEFAULT_INPUTS)
                  .build());
      assertThat(Arg.stringify(lib.getArgs()), hasItem(ent.getValue()));
    }
  }

  @Test
  public void getTransitiveNativeLinkableInputDoesNotTraversePastNonNativeLinkables()
      throws Exception {
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(
        new CxxBuckConfig(FakeBuckConfig.builder().build()));
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );

    // Create a native linkable that sits at the bottom of the dep chain.
    String sentinel = "bottom";
    NativeLinkableInput bottomInput = NativeLinkableInput.of(
        ImmutableList.of(new StringArg(sentinel)),
        ImmutableSet.of(),
        ImmutableSet.of());
    BuildRule bottom = createNativeLinkable("//:bottom", pathResolver, bottomInput, bottomInput);

    // Create a non-native linkable that sits in the middle of the dep chain, preventing
    // traversals to the bottom native linkable.
    BuildRule middle = new FakeBuildRule("//:middle", pathResolver, bottom);

    // Create a native linkable that sits at the top of the dep chain.
    NativeLinkableInput topInput = NativeLinkableInput.of(
        ImmutableList.of(),
        ImmutableSet.of(),
        ImmutableSet.of());
    BuildRule top = createNativeLinkable("//:top", pathResolver, topInput, topInput, middle);

    // Now grab all input via traversing deps and verify that the middle rule prevents pulling
    // in the bottom input.
    NativeLinkableInput totalInput =
        NativeLinkables.getTransitiveNativeLinkableInput(
            cxxPlatform,
            ImmutableList.of(top),
            Linker.LinkableDepType.STATIC,
            NativeLinkable.class::isInstance);
    assertThat(
        Arg.stringify(bottomInput.getArgs()),
        hasItem(sentinel));
    assertThat(
        Arg.stringify(totalInput.getArgs()),
        not(hasItem(sentinel)));
  }

  @Test
  public void machOBundleWithBundleLoaderHasExpectedArgs() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ProjectFilesystem filesystem = params.getProjectFilesystem();
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        params,
        resolver,
        new SourcePathResolver(resolver),
        target,
        Linker.LinkType.MACH_O_BUNDLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        EMPTY_DEPS,
        Optional.empty(),
        Optional.of(new FakeSourcePath(filesystem, "path/to/MyBundleLoader")),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(SourcePathArg.from(
                new SourcePathResolver(resolver),
                new FakeSourcePath("simple.o")))
            .build());
    assertThat(
        Arg.stringify(cxxLink.getArgs()),
        hasItem("-bundle"));
    assertThat(
        Arg.stringify(cxxLink.getArgs()),
        hasConsecutiveItems(
            "-bundle_loader",
            filesystem.resolve("path/to/MyBundleLoader").toString()));
  }

  @Test
  public void machOBundleSourcePathIsInDepsOfRule() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildTarget bundleLoaderTarget = BuildTargetFactory.newInstance("//foo:bundleLoader")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams bundleLoaderParams = new FakeBuildRuleParamsBuilder(bundleLoaderTarget).build();
    CxxLink bundleLoaderRule = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        bundleLoaderParams,
        resolver,
        new SourcePathResolver(resolver),
        bundleLoaderTarget,
        Linker.LinkType.EXECUTABLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        EMPTY_DEPS,
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(SourcePathArg.from(
                new SourcePathResolver(resolver),
                new FakeSourcePath("simple.o")))
            .build());
    resolver.addToIndex(bundleLoaderRule);

    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//foo:bundle")
        .withAppendedFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor());
    BuildRuleParams bundleParams = new FakeBuildRuleParamsBuilder(bundleTarget).build();
    CxxLink bundleRule = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        CxxPlatformUtils.DEFAULT_CONFIG,
        CXX_PLATFORM,
        bundleParams,
        resolver,
        new SourcePathResolver(resolver),
        bundleTarget,
        Linker.LinkType.MACH_O_BUNDLE,
        Optional.empty(),
        DEFAULT_OUTPUT,
        Linker.LinkableDepType.STATIC,
        EMPTY_DEPS,
        Optional.empty(),
        Optional.of(
            new BuildTargetSourcePath(bundleLoaderRule.getBuildTarget())),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(SourcePathArg.from(
                new SourcePathResolver(resolver),
                new FakeSourcePath("another.o")))
            .build());

    // Ensure the bundle depends on the bundle loader rule.
    assertThat(
        bundleRule.getDeps(),
        hasItem(bundleLoaderRule));
  }

  @Test
  public void frameworksToLinkerFlagsTransformer() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );

    Arg linkerFlags = CxxLinkableEnhancer.frameworksToLinkerArg(
        resolver,
        ImmutableSortedSet.of(
            FrameworkPath.ofSourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.DEVELOPER_DIR,
                    Paths.get("Library/Frameworks/XCTest.framework"),
                    Optional.empty())),
            FrameworkPath.ofSourcePath(
                new PathSourcePath(projectFilesystem, Paths.get("Vendor/Bar/Bar.framework")))));

    assertEquals(
        ImmutableList.of(
            "-framework", "XCTest",
            "-framework", "Bar"),
        Arg.stringifyList(linkerFlags));
  }
}
