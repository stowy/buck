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

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public class SwiftLibraryDescription implements
    Description<SwiftLibraryDescription.Arg>,
    Flavored {
  public static final BuildRuleType TYPE = BuildRuleType.of("swift_library");

  static final Flavor SWIFT_COMPANION_FLAVOR = ImmutableFlavor.of("swift-companion");
  static final Flavor SWIFT_COMPILE_FLAVOR = ImmutableFlavor.of("swift-compile");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      SWIFT_COMPANION_FLAVOR,
      SWIFT_COMPILE_FLAVOR,
      LinkerMapMode.NO_LINKER_MAP.getFlavor(),
      LinkerMapMode.LINKER_MAP.getFlavor());

  public enum Type implements FlavorConvertible {
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    ;

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("Swift Library Type", Type.class);


  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final FlavorDomain<SwiftPlatform> swiftPlatformFlavorDomain;

  public SwiftLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      SwiftBuckConfig swiftBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      FlavorDomain<SwiftPlatform> swiftPlatformFlavorDomain) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.swiftBuckConfig = swiftBuckConfig;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.swiftPlatformFlavorDomain = swiftPlatformFlavorDomain;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public SwiftLibraryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    ImmutableSet<Flavor> currentUnsupportedFlavors = ImmutableSet.copyOf(Sets.filter(
        flavors, Predicates.not(SUPPORTED_FLAVORS::contains)));
    if (currentUnsupportedFlavors.isEmpty()) {
      return true;
    }
    return cxxPlatformFlavorDomain.containsAnyOf(flavors);
  }

  @Override
  public <A extends SwiftLibraryDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    if (!params.getBuildTarget().getFlavors().contains(SWIFT_COMPILE_FLAVOR) &&
        !LinkerMapMode.FLAVOR_DOMAIN.containsAnyOf(params.getBuildTarget().getFlavors())) {
      return resolver.requireRule(
          LinkerMapMode.buildTargetByAddingDefaultLinkerMapFlavorIfNeeded(params.getBuildTarget())
              .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR));
    }

    Optional<LinkerMapMode> flavoredLinkerMapMode =
        LinkerMapMode.FLAVOR_DOMAIN.getValue(params.getBuildTarget());
    params = LinkerMapMode.removeLinkerMapModeFlavorInParams(params, flavoredLinkerMapMode);

    final BuildTarget buildTarget = params.getBuildTarget();

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, CxxPlatform>> platform = cxxPlatformFlavorDomain.getFlavorAndValue(
        buildTarget);
    final ImmutableSortedSet<Flavor> buildFlavors = buildTarget.getFlavors();
    ImmutableSortedSet<BuildRule> filteredExtraDeps =
        FluentIterable.from(params.getExtraDeps().get())
            .filter(input -> !input.getBuildTarget().getUnflavoredBuildTarget()
                .equals(buildTarget.getUnflavoredBuildTarget()))
            .toSortedSet(Ordering.natural());
    params = params.copyWithExtraDeps(Suppliers.ofInstance(filteredExtraDeps));

    if (!buildFlavors.contains(SWIFT_COMPANION_FLAVOR) && platform.isPresent()) {
      final CxxPlatform cxxPlatform = platform.get().getValue();
      Optional<SwiftPlatform> swiftPlatform = swiftPlatformFlavorDomain.getValue(buildTarget);
      if (!swiftPlatform.isPresent()) {
        throw new HumanReadableException("Platform %s is missing swift compiler", cxxPlatform);
      }

      // See if we're building a particular "type" and "platform" of this library, and if so,
      // extract them from the flavors attached to the build target.
      Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
      if (!buildFlavors.contains(SWIFT_COMPILE_FLAVOR) && type.isPresent()) {
        Set<Flavor> flavors = Sets.newHashSet(params.getBuildTarget().getFlavors());
        flavors.remove(type.get().getKey());
        BuildTarget target = BuildTarget
            .builder(params.getBuildTarget().getUnflavoredBuildTarget())
            .addAllFlavors(flavors)
            .addFlavors(flavoredLinkerMapMode.get().getFlavor())
            .build();
        BuildRuleParams typeParams =
            params.copyWithChanges(
                target,
                params.getDeclaredDeps(),
                params.getExtraDeps());

        switch (type.get().getValue()) {
          case SHARED:
            return createSharedLibraryBuildRule(
                typeParams,
                resolver,
                target,
                swiftPlatform.get(),
                cxxPlatform,
                args.soname,
                flavoredLinkerMapMode);
          case STATIC:
          case MACH_O_BUNDLE:
          // TODO(tho@uber.com) create build rule for other types.
        }
        throw new RuntimeException("unhandled library build type");
      }

      // All swift-compile rules of swift-lib deps are required since we need their swiftmodules
      // during compilation.
      final Function<BuildRule, BuildRule> requireSwiftCompile = input -> {
        try {
          Preconditions.checkArgument(input instanceof SwiftLibrary);
          return ((SwiftLibrary) input).requireSwiftCompileRule(cxxPlatform.getFlavor());
        } catch (NoSuchBuildTargetException e) {
          throw new HumanReadableException(e,
              "Could not find SwiftCompile with target %s", buildTarget);
        }
      };
      params = params.appendExtraDeps(
          params.getDeps().stream()
              .filter(SwiftLibrary.class::isInstance)
              .map(requireSwiftCompile)
              .collect(MoreCollectors.toImmutableSet()));

      params = params.appendExtraDeps(
          FluentIterable.from(params.getDeps())
              .filter(CxxLibrary.class)
              .transform(input -> {
                BuildTarget companionTarget = input.getBuildTarget().withAppendedFlavors(
                    SWIFT_COMPANION_FLAVOR);
                return resolver.getRuleOptional(companionTarget)
                    .map(requireSwiftCompile);
              })
              .filter(Optional::isPresent)
              .transform(Optional::get)
              .toSortedSet(Ordering.natural()));

      UnflavoredBuildTarget unflavoredBuildTarget =
          params.getBuildTarget().getUnflavoredBuildTarget();

      for (HeaderVisibility headerVisibility : HeaderVisibility.values()) {
        // unflavoredBuildTarget because #headers can collide with any other flavor
        // from the same domain.
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params.copyWithBuildTarget(BuildTarget.builder(unflavoredBuildTarget).build()),
            resolver,
            new SourcePathResolver(resolver),
            cxxPlatform,
            args.headersSearchPath,
            headerVisibility);
      }

      return new SwiftCompile(
          cxxPlatform,
          swiftBuckConfig,
          params,
          new SourcePathResolver(resolver),
          swiftPlatform.get().getSwift(),
          args.frameworks,
          args.moduleName.orElse(buildTarget.getShortName()),
          BuildTargets.getGenPath(
              params.getProjectFilesystem(),
              buildTarget, "%s"),
          args.srcs,
          args.enableObjcInterop,
          args.bridgingHeader);
    }

    // Otherwise, we return the generic placeholder of this library.
    params = LinkerMapMode.restoreLinkerMapModeFlavorInParams(params, flavoredLinkerMapMode);
    return new SwiftLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        ImmutableSet.of(),
        swiftPlatformFlavorDomain,
        args.frameworks,
        args.libraries,
        args.supportedPlatformsRegex,
        args.preferredLinkage.orElse(NativeLinkable.Linkage.ANY));
  }

  private BuildRule createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      BuildTarget buildTarget,
      SwiftPlatform swiftPlatform,
      CxxPlatform cxxPlatform,
      Optional<String> soname,
      Optional<LinkerMapMode> flavoredLinkerMapMode) throws NoSuchBuildTargetException {

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    String sharedLibrarySoname = CxxDescriptionEnhancer.getSharedLibrarySoname(
        soname,
        buildTarget.withoutFlavors(SUPPORTED_FLAVORS),
        cxxPlatform);
    Path sharedLibOutput = CxxDescriptionEnhancer.getSharedLibraryPath(
        params.getProjectFilesystem(),
        buildTarget,
        sharedLibrarySoname);

    SwiftRuntimeNativeLinkable swiftRuntimeLinkable =
        new SwiftRuntimeNativeLinkable(swiftPlatform);

    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder()
        .from(swiftRuntimeLinkable.getNativeLinkableInput(
                cxxPlatform, Linker.LinkableDepType.SHARED));
    BuildTarget requiredBuildTarget = buildTarget
        .withoutFlavors(ImmutableSet.of(CxxDescriptionEnhancer.SHARED_FLAVOR))
        .withoutFlavors(LinkerMapMode.FLAVOR_DOMAIN.getFlavors())
        .withAppendedFlavors(SWIFT_COMPILE_FLAVOR);
    SwiftCompile rule = (SwiftCompile) resolver.requireRule(requiredBuildTarget);
    inputBuilder.addAllArgs(rule.getLinkArgs());
    return resolver.addToIndex(CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        LinkerMapMode.restoreLinkerMapModeFlavorInParams(params, flavoredLinkerMapMode),
        resolver,
        sourcePathResolver,
        buildTarget,
        Linker.LinkType.SHARED,
        Optional.of(sharedLibrarySoname),
        sharedLibOutput,
        Linker.LinkableDepType.SHARED,
        FluentIterable.from(params.getDeps())
            .filter(NativeLinkable.class)
            .append(swiftRuntimeLinkable),
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        inputBuilder.build()));
  }

  public <A extends CxxLibraryDescription.Arg> Optional<BuildRule> createCompanionBuildRule(
      final TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    BuildTarget buildTarget = params.getBuildTarget();
    if (!isSwiftTarget(buildTarget)) {
      boolean hasSwiftSource = !SwiftDescriptions.filterSwiftSources(
          new SourcePathResolver(resolver),
          args.srcs).isEmpty();
      return hasSwiftSource ?
          Optional.of(resolver.requireRule(buildTarget.withAppendedFlavors(SWIFT_COMPANION_FLAVOR)))
          : Optional.empty();
    }

    final SwiftLibraryDescription.Arg delegateArgs = createUnpopulatedConstructorArg();
    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        new SourcePathResolver(resolver),
        delegateArgs,
        args,
        buildTarget);
    if (!delegateArgs.srcs.isEmpty()) {
      return Optional.of(
          resolver.addToIndex(
              createBuildRule(targetGraph, params, resolver, delegateArgs)));
    } else {
      return Optional.empty();
    }
  }

  public static boolean isSwiftTarget(BuildTarget buildTarget) {
    return buildTarget.getFlavors().contains(SWIFT_COMPANION_FLAVOR) ||
        buildTarget.getFlavors().contains(SWIFT_COMPILE_FLAVOR);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<String> moduleName;
    public ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
    public ImmutableList<String> compilerFlags = ImmutableList.of();
    public ImmutableSortedSet<FrameworkPath> frameworks = ImmutableSortedSet.of();
    public ImmutableSortedSet<FrameworkPath> libraries = ImmutableSortedSet.of();
    public Optional<Boolean> enableObjcInterop;
    public Optional<Pattern> supportedPlatformsRegex;
    public Optional<String> soname;
    public Optional<SourcePath> bridgingHeader;
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public Optional<NativeLinkable.Linkage> preferredLinkage;
    public ImmutableMap<Path, SourcePath> headersSearchPath = ImmutableMap.of();
  }

}
