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

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class AppleBundleDescription implements Description<AppleBundleDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<AppleBundleDescription.Arg>,
    MetadataProvidingDescription<AppleBundleDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_bundle");
  public static final ImmutableSet<Flavor> SUPPORTED_LIBRARY_FLAVORS = ImmutableSet.of(
      CxxDescriptionEnhancer.STATIC_FLAVOR,
      CxxDescriptionEnhancer.SHARED_FLAVOR);

  public static final Flavor WATCH_OS_FLAVOR = ImmutableFlavor.of("watchos-armv7k");
  public static final Flavor WATCH_SIMULATOR_FLAVOR = ImmutableFlavor.of("watchsimulator-i386");

  private static final Flavor WATCH = ImmutableFlavor.of("watch");

  private final AppleBinaryDescription appleBinaryDescription;
  private final AppleLibraryDescription appleLibraryDescription;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformsFlavorDomain;
  private final CxxPlatform defaultCxxPlatform;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final AppleDebugFormat defaultDebugFormat;

  public AppleBundleDescription(
      AppleBinaryDescription appleBinaryDescription,
      AppleLibraryDescription appleLibraryDescription,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformsFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      AppleDebugFormat defaultDebugFormat) {
    this.appleBinaryDescription = appleBinaryDescription;
    this.appleLibraryDescription = appleLibraryDescription;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.appleCxxPlatformsFlavorDomain = appleCxxPlatformsFlavorDomain;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.defaultDebugFormat = defaultDebugFormat;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(final ImmutableSet<Flavor> flavors) {
    if (appleLibraryDescription.hasFlavors(flavors)) {
      return true;
    }
    ImmutableSet.Builder<Flavor> flavorBuilder = ImmutableSet.builder();
    for (Flavor flavor : flavors) {
      if (AppleDebugFormat.FLAVOR_DOMAIN.getFlavors().contains(flavor)) {
        continue;
      }
      if (AppleDescriptions.INCLUDE_FRAMEWORKS.getFlavors().contains(flavor)) {
        continue;
      }
      flavorBuilder.add(flavor);
    }
    return appleBinaryDescription.hasFlavors(flavorBuilder.build());
  }


  @Override
  public <A extends Arg> AppleBundle createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    AppleDebugFormat flavoredDebugFormat = AppleDebugFormat.FLAVOR_DOMAIN
        .getValue(params.getBuildTarget()).orElse(defaultDebugFormat);
    if (!params.getBuildTarget().getFlavors().contains(flavoredDebugFormat.getFlavor())) {
      return (AppleBundle) resolver.requireRule(
          params.getBuildTarget().withAppendedFlavors(flavoredDebugFormat.getFlavor()));
    }
    if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(params.getBuildTarget()).isPresent()) {
      return (AppleBundle) resolver.requireRule(
          params.getBuildTarget().withAppendedFlavors(
              AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR));
    }
    return AppleDescriptions.createAppleBundle(
        cxxPlatformFlavorDomain,
        defaultCxxPlatform,
        appleCxxPlatformsFlavorDomain,
        targetGraph,
        params,
        resolver,
        codeSignIdentityStore,
        provisioningProfileStore,
        args.binary,
        args.extension,
        args.productName,
        args.infoPlist,
        args.infoPlistSubstitutions,
        args.deps,
        args.tests,
        flavoredDebugFormat);
  }

  /**
   * Propagate the bundle's platform, debug symbol and strip flavors to its dependents
   * which are other bundles (e.g. extensions)
   */
  @Override
  public ImmutableSet<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AppleBundleDescription.Arg constructorArg) {
    if (!cxxPlatformFlavorDomain.containsAnyOf(buildTarget.getFlavors())) {
      buildTarget = BuildTarget.builder(buildTarget).addAllFlavors(
          ImmutableSet.of(defaultCxxPlatform.getFlavor())).build();
    }

    Optional<MultiarchFileInfo> fatBinaryInfo =
        MultiarchFileInfos.create(appleCxxPlatformsFlavorDomain, buildTarget);
    CxxPlatform cxxPlatform;
    if (fatBinaryInfo.isPresent()) {
      AppleCxxPlatform appleCxxPlatform = fatBinaryInfo.get().getRepresentativePlatform();
      cxxPlatform = appleCxxPlatform.getCxxPlatform();
    } else {
      cxxPlatform = ApplePlatforms.getCxxPlatformForBuildTarget(
          cxxPlatformFlavorDomain,
          defaultCxxPlatform,
          buildTarget);
    }

    String platformName = cxxPlatform.getFlavor().getName();
    final Flavor actualWatchFlavor;
    if (ApplePlatform.isSimulator(platformName)) {
      actualWatchFlavor = WATCH_SIMULATOR_FLAVOR;
    } else if (platformName.startsWith(ApplePlatform.IPHONEOS.getName()) ||
        platformName.startsWith(ApplePlatform.WATCHOS.getName())) {
      actualWatchFlavor = WATCH_OS_FLAVOR;
    } else {
      actualWatchFlavor = ImmutableFlavor.of(platformName);
    }

    FluentIterable<BuildTarget> depsExcludingBinary = FluentIterable.from(constructorArg.deps)
        .filter(Predicates.not(constructorArg.binary::equals));

    // Propagate platform flavors.  Need special handling for watch to map the pseudo-flavor
    // watch to the actual watch platform (simulator or device) so can't use
    // BuildTargets.propagateFlavorsInDomainIfNotPresent()
    {
      FluentIterable<BuildTarget> targetsWithPlatformFlavors = depsExcludingBinary.filter(
          BuildTargets.containsFlavors(cxxPlatformFlavorDomain));

      FluentIterable<BuildTarget> targetsWithoutPlatformFlavors = depsExcludingBinary.filter(
          Predicates.not(BuildTargets.containsFlavors(cxxPlatformFlavorDomain)));

      FluentIterable<BuildTarget> watchTargets = targetsWithoutPlatformFlavors
          .filter(BuildTargets.containsFlavor(WATCH))
          .transform(
              input -> BuildTarget.builder(
                  input.withoutFlavors(ImmutableSet.of(WATCH)))
                  .addFlavors(actualWatchFlavor)
                  .build());

      targetsWithoutPlatformFlavors = targetsWithoutPlatformFlavors
          .filter(Predicates.not(BuildTargets.containsFlavor(WATCH)));

      // Gather all the deps now that we've added platform flavors to everything.
      depsExcludingBinary = targetsWithPlatformFlavors
          .append(watchTargets)
          .append(BuildTargets.propagateFlavorDomains(
              buildTarget,
              ImmutableSet.of(cxxPlatformFlavorDomain),
              targetsWithoutPlatformFlavors));
    }

    // Propagate some flavors in defined
    depsExcludingBinary = BuildTargets.propagateFlavorsInDomainIfNotPresent(
        StripStyle.FLAVOR_DOMAIN,
        buildTarget,
        depsExcludingBinary);
    depsExcludingBinary = BuildTargets.propagateFlavorsInDomainIfNotPresent(
        AppleDebugFormat.FLAVOR_DOMAIN,
        buildTarget,
        depsExcludingBinary);
    depsExcludingBinary = BuildTargets.propagateFlavorsInDomainIfNotPresent(
        LinkerMapMode.FLAVOR_DOMAIN,
        buildTarget,
        depsExcludingBinary);

    return ImmutableSet.copyOf(depsExcludingBinary);
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    return resolver.requireMetadata(args.binary, metadataClass);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg implements HasAppleBundleFields, HasTests {
    public Either<AppleBundleExtension, String> extension;
    public BuildTarget binary;
    public SourcePath infoPlist;
    public ImmutableMap<String, String> infoPlistSubstitutions = ImmutableMap.of();
    @Hint(isDep = false) public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    @Hint(isDep = false) public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();
    public Optional<String> xcodeProductType;
    public Optional<String> productName;

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return extension;
    }

    @Override
    public SourcePath getInfoPlist() {
      return infoPlist;
    }

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests;
    }

    @Override
    public Optional<String> getXcodeProductType() {
      return xcodeProductType;
    }

    @Override
    public Optional<String> getProductName() {
      return productName;
    }
  }
}
