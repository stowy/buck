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

package com.facebook.buck.cxx;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public final class HeaderSymlinkTreeWithHeaderMap extends HeaderSymlinkTree {

  private static final Logger LOG = Logger.get(HeaderSymlinkTreeWithHeaderMap.class);
  private static final String MODULE_MAP = "module.modulemap";
  private static final Path MODULEMAP_TEMPLATE_PATH = Paths.get(
      Resources.getResource(HeaderSymlinkTreeWithHeaderMap.class, "modulemap.st").getPath());

  private static final Path UMBRELLA_HEADER_TEMPLATE_PATH = Paths.get(
      Resources.getResource(HeaderSymlinkTreeWithHeaderMap.class, "umbrella_header.st").getPath());

  @AddToRuleKey(stringify = true)
  private final Path headerMapPath;

  @AddToRuleKey
  private final boolean shouldCreateModule;

  private HeaderSymlinkTreeWithHeaderMap(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      Path headerMapPath,
      boolean shouldCreateModule) {
    super(params, resolver, root, links);
    this.headerMapPath = headerMapPath;
    this.shouldCreateModule = shouldCreateModule;
  }

  public static HeaderSymlinkTreeWithHeaderMap create(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path root,
      ImmutableMap<Path, SourcePath> links) {
    Path headerMapPath = getPath(params.getProjectFilesystem(), params.getBuildTarget());
    boolean shouldCreateModule = params.getBuildTarget().getFlavors()
        .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);
    return new HeaderSymlinkTreeWithHeaderMap(
        params,
        resolver,
        root,
        links,
        headerMapPath,
        shouldCreateModule);
  }

  @Override
  public Path getPathToOutput() {
    return headerMapPath;
  }

  // We generate the symlink tree and the header map using post-build steps for reasons explained in
  // the superclass.
  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    LOG.debug("Generating post-build steps to write header map to %s", headerMapPath);
    Path buckOut =
        getProjectFilesystem().resolve(getProjectFilesystem().getBuckPaths().getBuckOut());
    ImmutableMap.Builder<Path, Path> headerMapEntries = ImmutableMap.builder();
    for (Path key : getLinks().keySet()) {
      // The key is the path that will be referred to in headers. It can be anything. However, the
      // value given in the headerMapEntries is the path of that entry in the generated symlink
      // tree. Because "reasons", we don't want to cache that value, so we need to relativize the
      // path to the output directory of this current rule. We then rely on magic and the stars
      // aligning in order to get this to work. May we find peace in another life.
      headerMapEntries.put(key, buckOut.relativize(getRoot().resolve(key)));
    }
    ImmutableList.Builder<Step> builder = ImmutableList.<Step>builder()
        .addAll(super.getBuildSteps(context, buildableContext))
        .add(new HeaderMapStep(getProjectFilesystem(), headerMapPath, headerMapEntries.build()));

    if (shouldCreateModule) {
      String moduleName = getBuildTarget().getShortName();
      String umbrellaHeaderName = moduleName + "-umbrella.h";
      builder
          .add(createUmbrellaHeaderStep(umbrellaHeaderName))
          .add(createCreateModuleStep(moduleName, umbrellaHeaderName));
    }
    return builder.build();
  }

  // TODO(nguyentruongtho) use existing umbrella header is present, umbrella header file name
  // should be named in this format: <module_name>-umbrella.h
  private Step createUmbrellaHeaderStep(
      String umbrellaHeaderName) {
    return new StringTemplateStep(
        UMBRELLA_HEADER_TEMPLATE_PATH,
        getProjectFilesystem(),
        getProjectFilesystem().relativize(getRoot().resolve(umbrellaHeaderName)),
        input -> input
            .add("exported_headers", getLinks().keySet()));
  }

  private Step createCreateModuleStep(
      String moduleName,
      String umbrellaHeaderFilename) {
    return new StringTemplateStep(
        MODULEMAP_TEMPLATE_PATH,
        getProjectFilesystem(),
        getProjectFilesystem().relativize(getRoot().resolve(MODULE_MAP)),
        input -> input
            .add("module_name", moduleName)
            .add("umbrella_header_name", umbrellaHeaderFilename));
  }

  @Override
  public Path getIncludePath() {
    return getProjectFilesystem().resolve(getProjectFilesystem().getBuckPaths().getBuckOut());
  }

  @Override
  public Optional<Path> getHeaderMap() {
    return Optional.of(getProjectFilesystem().resolve(headerMapPath));
  }

  @VisibleForTesting
  static Path getPath(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargets.getGenPath(filesystem, target, "%s.hmap");
  }
}
