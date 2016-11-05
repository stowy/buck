/*
 * Copyright 2013-present Facebook, Inc.
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

import static com.facebook.buck.swift.SwiftLibraryDescription.isSwiftTarget;

import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.ProvidesLinkedBinaryDeps;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public class AppleBinaryDescription implements
    Description<AppleBinaryDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<AppleBinaryDescription.Arg>,
    ImplicitFlavorsInferringDescription,
    MetadataProvidingDescription<AppleBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("apple_binary");
  public static final Flavor APP_FLAVOR = ImmutableFlavor.of("app");
  public static final Sets.SetView<Flavor> NON_DELEGATE_FLAVORS = Sets.union(
      AppleDebugFormat.FLAVOR_DOMAIN.getFlavors(),
      ImmutableSet.of(APP_FLAVOR));
  public static final Flavor LEGACY_WATCH_FLAVOR = ImmutableFlavor.of("legacy_watch");

  @SuppressWarnings("PMD") // PMD doesn't understand method references
  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      APP_FLAVOR,
      CxxCompilationDatabase.COMPILATION_DATABASE,
      CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
      AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
      AppleDebugFormat.DWARF.getFlavor(),
      AppleDebugFormat.NONE.getFlavor(),
      LinkerMapMode.LINKER_MAP.getFlavor(),
      LinkerMapMode.NO_LINKER_MAP.getFlavor());

  private final CxxBinaryDescription delegate;
  private final SwiftLibraryDescription swiftDelegate;
  private final FlavorDomain<AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final AppleDebugFormat defaultDebugFormat;

  public AppleBinaryDescription(
      CxxBinaryDescription delegate,
      SwiftLibraryDescription swiftDelegate,
      FlavorDomain<AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleDebugFormat defaultDebugFormat) {
    this.delegate = delegate;
    this.swiftDelegate = swiftDelegate;
    this.platformFlavorsToAppleCxxPlatforms = platformFlavorsToAppleCxxPlatforms;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.defaultDebugFormat = defaultDebugFormat;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleBinaryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (FluentIterable.from(flavors).allMatch(SUPPORTED_FLAVORS::contains)) {
      return true;
    }
    ImmutableSet<Flavor> delegateFlavors = ImmutableSet.copyOf(Sets.difference(
        flavors, NON_DELEGATE_FLAVORS));
    if (swiftDelegate.hasFlavors(delegateFlavors)) {
      return true;
    }
    Collection<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinDelegateFlavors(delegateFlavors);
    if (thinFlavorSets.size() > 0) {
      return Iterables.all(
          thinFlavorSets,
          delegate::hasFlavors);
    } else {
      return delegate.hasFlavors(delegateFlavors);
    }
  }

  private Collection<ImmutableSortedSet<Flavor>> generateThinDelegateFlavors(
      ImmutableSet<Flavor> delegateFlavors) {
    return MultiarchFileInfos.generateThinFlavors(
        platformFlavorsToAppleCxxPlatforms.getFlavors(),
        ImmutableSortedSet.copyOf(delegateFlavors));
  }

  @Override
  public <A extends AppleBinaryDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    if (params.getBuildTarget().getFlavors().contains(APP_FLAVOR)) {
      return createBundleBuildRule(targetGraph, params, resolver, args);
    } else {
      return createBinaryBuildRule(targetGraph, params, resolver, args);
    }
  }

  // We want to wrap only if we have explicit debug flavor. This is because we don't want to
  // force dSYM generation in case if its enabled by default in config. We just want the binary,
  // so unless flavor is explicitly set, lets just produce binary!
  private boolean shouldWrapIntoAppleDebuggableBinary(
      BuildTarget buildTarget,
      BuildRule binaryBuildRule) {
    Optional<AppleDebugFormat> explicitDebugInfoFormat =
        AppleDebugFormat.FLAVOR_DOMAIN.getValue(buildTarget);
    boolean binaryIsWrappable = AppleDebuggableBinary.canWrapBinaryBuildRule(binaryBuildRule);
    return explicitDebugInfoFormat.isPresent() && binaryIsWrappable;
  }

  private <A extends Arg> BuildRule createBinaryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<BuildRule> swiftCompanionBuildRule = swiftDelegate.createCompanionBuildRule(
        targetGraph, params, resolver, args);
    if (swiftCompanionBuildRule.isPresent()) {
      // when creating a swift target, there is no need to proceed with apple binary rules,
      // otherwise, add this swift rule as a dependency.
      if (isSwiftTarget(params.getBuildTarget())) {
        return swiftCompanionBuildRule.get();
      } else {
        params = params.appendExtraDeps(ImmutableSet.of(swiftCompanionBuildRule.get()));
      }
    }
    // remove some flavors so binary will have the same output regardless their values
    BuildTarget unstrippedBinaryBuildTarget = params.getBuildTarget()
        .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
        .withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors());
    unstrippedBinaryBuildTarget = LinkerMapMode
        .buildTargetByAddingDefaultLinkerMapFlavorIfNeeded(unstrippedBinaryBuildTarget);

    BuildRule unstrippedBinaryRule = createBinary(
        targetGraph,
        params.copyWithBuildTarget(unstrippedBinaryBuildTarget),
        resolver,
        args);

    if (shouldWrapIntoAppleDebuggableBinary(params.getBuildTarget(), unstrippedBinaryRule)) {
      return createAppleDebuggableBinary(
          targetGraph,
          params,
          resolver,
          args,
          unstrippedBinaryBuildTarget,
          (ProvidesLinkedBinaryDeps) unstrippedBinaryRule);
    } else {
      return unstrippedBinaryRule;
    }
  }

  private <A extends Arg> BuildRule createAppleDebuggableBinary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      BuildTarget unstrippedBinaryBuildTarget,
      ProvidesLinkedBinaryDeps unstrippedBinaryRule) throws NoSuchBuildTargetException {
    BuildTarget strippedBinaryBuildTarget = unstrippedBinaryBuildTarget
        .withAppendedFlavors(
            CxxStrip.RULE_FLAVOR,
            StripStyle.FLAVOR_DOMAIN.getFlavor(params.getBuildTarget().getFlavors())
                .orElse(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor()));
    BuildRule strippedBinaryRule = createBinary(
        targetGraph,
        params.copyWithBuildTarget(strippedBinaryBuildTarget),
        resolver,
        args);
    return AppleDescriptions.createAppleDebuggableBinary(
        params.copyWithBuildTarget(unstrippedBinaryBuildTarget),
        resolver,
        strippedBinaryRule,
        unstrippedBinaryRule,
        AppleDebugFormat.FLAVOR_DOMAIN.getRequiredValue(params.getBuildTarget()),
        delegate.getCxxPlatforms(),
        delegate.getDefaultCxxPlatform(),
        platformFlavorsToAppleCxxPlatforms);
  }

  private <A extends Arg> BuildRule createBundleBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    if (!args.infoPlist.isPresent()) {
      throw new HumanReadableException(
          "Cannot create application for apple_binary '%s':\n",
          "No value specified for 'info_plist' attribute.",
          params.getBuildTarget().getUnflavoredBuildTarget());
    }
    AppleDebugFormat flavoredDebugFormat = AppleDebugFormat.FLAVOR_DOMAIN
        .getValue(params.getBuildTarget()).orElse(defaultDebugFormat);
    if (!params.getBuildTarget().getFlavors().contains(flavoredDebugFormat.getFlavor())) {
      return resolver.requireRule(
          params.getBuildTarget().withAppendedFlavors(flavoredDebugFormat.getFlavor()));
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(params.getBuildTarget()).isPresent()) {
      CxxPlatform cxxPlatform = delegate.getCxxPlatforms().getValue(params.getBuildTarget())
          .orElse(delegate.getDefaultCxxPlatform());
      ApplePlatform applePlatform =
          platformFlavorsToAppleCxxPlatforms.getValue(cxxPlatform.getFlavor())
              .getAppleSdk()
              .getApplePlatform();
      if (applePlatform.getAppIncludesFrameworks()) {
        return resolver.requireRule(
            params.getBuildTarget().withAppendedFlavors(
                AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
      }
      return resolver.requireRule(
          params.getBuildTarget().withAppendedFlavors(
              AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR));
    }
    BuildTarget binaryTarget = params.withoutFlavor(APP_FLAVOR).getBuildTarget();
    return AppleDescriptions.createAppleBundle(
        delegate.getCxxPlatforms(),
        delegate.getDefaultCxxPlatform(),
        platformFlavorsToAppleCxxPlatforms,
        targetGraph,
        params,
        resolver,
        codeSignIdentityStore,
        provisioningProfileStore,
        binaryTarget,
        Either.ofLeft(AppleBundleExtension.APP),
        Optional.empty(),
        args.infoPlist.get(),
        args.infoPlistSubstitutions,
        args.deps,
        args.tests,
        flavoredDebugFormat);
  }

  private <A extends AppleBinaryDescription.Arg> BuildRule createBinary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    if (AppleDescriptions.flavorsDoNotAllowLinkerMapMode(params.getBuildTarget().getFlavors())) {
      params = params.copyWithBuildTarget(
          params.getBuildTarget().withoutFlavors(LinkerMapMode.FLAVOR_DOMAIN.getFlavors()));
    }

    Optional<MultiarchFileInfo> fatBinaryInfo = MultiarchFileInfos.create(
        platformFlavorsToAppleCxxPlatforms,
        params.getBuildTarget());
    if (fatBinaryInfo.isPresent()) {
      if (shouldUseStubBinary(params)) {
        BuildTarget thinTarget = Iterables.getFirst(fatBinaryInfo.get().getThinTargets(), null);
        return requireThinBinary(
            targetGraph,
            params.copyWithBuildTarget(thinTarget),
            resolver,
            args);
      }

      ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
      for (BuildTarget thinTarget : fatBinaryInfo.get().getThinTargets()) {
        Optional<BuildRule> existingThinRule = resolver.getRuleOptional(thinTarget);
        if (existingThinRule.isPresent()) {
          thinRules.add(existingThinRule.get());
          continue;
        }
        BuildRule thinRule = requireThinBinary(
            targetGraph,
            params.copyWithBuildTarget(thinTarget),
            resolver,
            args);
        resolver.addToIndex(thinRule);
        thinRules.add(thinRule);
      }
      return MultiarchFileInfos.requireMultiarchRule(
          params,
          resolver,
          fatBinaryInfo.get(),
          thinRules.build());
    } else {
      return requireThinBinary(targetGraph, params, resolver, args);
    }
  }

  private <A extends Arg> BuildRule requireThinBinary(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<BuildRule> existingThinRule = resolver.getRuleOptional(params.getBuildTarget());
    if (existingThinRule.isPresent()) {
      return existingThinRule.get();
    }

    Optional<Path> stubBinaryPath = getStubBinaryPath(params, args);
    if (shouldUseStubBinary(params) && stubBinaryPath.isPresent()) {
      try {
        return resolver.addToIndex(
            new WriteFile(
                params,
                new SourcePathResolver(resolver),
                Files.readAllBytes(stubBinaryPath.get()),
                BuildTargets.getGenPath(
                    params.getProjectFilesystem(),
                    params.getBuildTarget(),
                    "%s"),
                true));
      } catch (IOException e) {
        throw new HumanReadableException("Could not read stub binary " + stubBinaryPath.get());
      }
    } else {
      CxxBinaryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
      AppleDescriptions.populateCxxBinaryDescriptionArg(
          new SourcePathResolver(resolver),
          delegateArg,
          args,
          params.getBuildTarget());
      return resolver.addToIndex(
          delegate.createBuildRule(targetGraph, params, resolver, delegateArg));
    }
  }

  private boolean shouldUseStubBinary(BuildRuleParams params) {
    ImmutableSortedSet<Flavor> flavors = params.getBuildTarget().getFlavors();
    return (flavors.contains(AppleBundleDescription.WATCH_OS_FLAVOR) ||
        flavors.contains(AppleBundleDescription.WATCH_SIMULATOR_FLAVOR) ||
        flavors.contains(LEGACY_WATCH_FLAVOR));
  }


  private <A extends Arg> Optional<Path> getStubBinaryPath(BuildRuleParams params, A args) {
    Optional<Path> stubBinaryPath = Optional.empty();
    Optional<AppleCxxPlatform> appleCxxPlatform = getAppleCxxPlatformFromParams(params);
    if (appleCxxPlatform.isPresent() && args.srcs.isEmpty()) {
      stubBinaryPath = appleCxxPlatform.get().getStubBinary();
    }
    return stubBinaryPath;
  }

  private Optional<AppleCxxPlatform> getAppleCxxPlatformFromParams(BuildRuleParams params) {
    return platformFlavorsToAppleCxxPlatforms.getValue(params.getBuildTarget());
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (!metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      CxxBinaryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
      AppleDescriptions.populateCxxBinaryDescriptionArg(
          new SourcePathResolver(resolver),
          delegateArg,
          args,
          buildTarget);
      return delegate.createMetadata(buildTarget, resolver, delegateArg, metadataClass);
    }

    Optional<Flavor> cxxPlatformFlavor = delegate.getCxxPlatforms().getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : args.deps) {
      Optional<FrameworkDependencies> frameworks =
          resolver.requireMetadata(
              BuildTarget.builder(dep)
                  .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                  .addFlavors(cxxPlatformFlavor.get())
                  .build(),
              FrameworkDependencies.class);
      if (frameworks.isPresent()) {
        sourcePaths.addAll(frameworks.get().getSourcePaths());
      }
    }

    return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths.build())));
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
        ImmutableSortedSet<Flavor> argDefaultFlavors) {
    // Use defaults.apple_binary if present, but fall back to defaults.cxx_binary otherwise.
    return delegate.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors,
        TYPE,
        CxxBinaryDescription.TYPE);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      final BuildTarget buildTarget,
      final CellPathResolver cellRoots,
      final Arg constructorArg) {
    Collection<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinDelegateFlavors(buildTarget.getFlavors());
    if (thinFlavorSets.size() > 0) {
      return Iterables.concat(
          Iterables.transform(
              thinFlavorSets,
              input -> delegate.findDepsForTargetFromConstructorArgs(
                  buildTarget.withFlavors(input),
                  cellRoots,
                  constructorArg.linkerFlags,
                  constructorArg.platformLinkerFlags.getValues()))
      );
    } else {
      return delegate.findDepsForTargetFromConstructorArgs(
          buildTarget,
          cellRoots,
          constructorArg.linkerFlags,
          constructorArg.platformLinkerFlags.getValues());
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg {
    public Optional<SourcePath> infoPlist;
    public ImmutableMap<String, String> infoPlistSubstitutions = ImmutableMap.of();
  }

}
