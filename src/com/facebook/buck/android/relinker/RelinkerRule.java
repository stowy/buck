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
package com.facebook.buck.android.relinker;

import com.facebook.buck.android.NdkCxxPlatforms;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.OverrideScheduleRule;
import com.facebook.buck.rules.RuleScheduleInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import javax.annotation.Nullable;

class RelinkerRule extends AbstractBuildRule implements OverrideScheduleRule {

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> symbolsNeededPaths;
  @AddToRuleKey
  private final NdkCxxPlatforms.TargetCpuType cpuType;
  @AddToRuleKey
  private final SourcePath baseLibSourcePath;
  @AddToRuleKey
  private final Tool objdump;
  @AddToRuleKey
  private final Boolean isRelinkable;
  @AddToRuleKey
  private final ImmutableList<Arg> linkerArgs;
  @AddToRuleKey
  private final Linker linker;

  private final BuildRuleParams buildRuleParams;
  private final CxxBuckConfig cxxBuckConfig;

  public RelinkerRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> symbolsNeededPaths,
      NdkCxxPlatforms.TargetCpuType cpuType,
      Tool objdump,
      CxxBuckConfig cxxBuckConfig,
      SourcePath baseLibSourcePath,
      boolean isRelinkable,
      Linker linker,
      ImmutableList<Arg> linkerArgs) {
    super(withDepsFromArgs(buildRuleParams, resolver, linkerArgs), resolver);
    this.cpuType = cpuType;
    this.objdump = objdump;
    this.cxxBuckConfig = cxxBuckConfig;
    this.isRelinkable = isRelinkable;
    this.linkerArgs = linkerArgs;
    this.buildRuleParams = buildRuleParams;
    this.symbolsNeededPaths = symbolsNeededPaths;
    this.baseLibSourcePath = baseLibSourcePath;
    this.linker = linker;
  }

  private static BuildRuleParams withDepsFromArgs(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableList<Arg> args) {
    return params.appendExtraDeps(
        Iterables.concat(Iterables.transform(args, arg -> arg.getDeps(resolver))));
  }

  private static String getVersionScript(Set<String> needed, Set<String> provided) {
    Set<String> keep = new ImmutableSet.Builder<String>()
        .addAll(Sets.intersection(needed, provided))
        .addAll(
            Sets.filter(
                provided, s -> {
                  if (s.contains("JNI_OnLoad")) {
                    return true;
                  }
                  if (s.contains("Java_")) {
                    return true;
                  }
                  return false;
                }))
        .build();
    String res = "{\n";
    if (!keep.isEmpty()) {
      res += "global:\n";
    }
    for (String s : keep) {
      res += "  " + s + ";\n";
    }
    res += "local: *;\n};\n";
    return res;
  }

  public SourcePath getLibFileSourcePath() {
    return new BuildTargetSourcePath(buildRuleParams.getBuildTarget(), getLibFilePath());
  }

  public SourcePath getSymbolsNeededPath() {
    return new BuildTargetSourcePath(buildRuleParams.getBuildTarget(), getSymbolsNeededOutPath());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {

    final ImmutableList.Builder<Step> relinkerSteps = ImmutableList.builder();
    if (isRelinkable) {
      ImmutableList<Arg> args = ImmutableList.<Arg>builder()
          .addAll(linkerArgs)
          .add(
              new StringArg(
                  "-Wl,--version-script=" + getRelativeVersionFilePath().toString()))
          .build();

      relinkerSteps.addAll(
          new CxxLink(
              buildRuleParams
                  .withFlavor(ImmutableFlavor.of("cxx-link"))
                  .withFlavor(getLinkerMapFlavor()),
              getResolver(),
              linker,
              getLibFilePath(),
              args,
              cxxBuckConfig.getLinkScheduleInfo(),
              cxxBuckConfig.shouldCacheLinks())
              .getBuildSteps(context, buildableContext));
      buildableContext.recordArtifact(getRelativeVersionFilePath());
    }

    buildableContext.recordArtifact(getSymbolsNeededOutPath());

    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), getScratchDirPath()),
        new AbstractExecutionStep("xdso-dce relinker") {
          @Override
          public StepExecutionResult execute(ExecutionContext context)
              throws IOException, InterruptedException {
            ImmutableSet<String> symbolsNeeded = readSymbolsNeeded();
            if (!isRelinkable) {
              getProjectFilesystem().copyFile(getBaseLibPath(), getLibFilePath());
              buildableContext.recordArtifact(getLibFilePath());
            } else {
              writeVersionScript(context.getProcessExecutor(), symbolsNeeded);
              for (Step s : relinkerSteps.build()) {
                StepExecutionResult executionResult = s.execute(context);
                if (!executionResult.isSuccess()) {
                  return StepExecutionResult.ERROR;
                }
              }
            }
            writeSymbols(
                getSymbolsNeededOutPath(),
                Sets.union(
                    symbolsNeeded,
                    getSymbols(context.getProcessExecutor(), getLibFilePath()).undefined));
            return StepExecutionResult.SUCCESS;
          }
        });
  }

  private Flavor getLinkerMapFlavor() {
    BuildTarget buildTarget = buildRuleParams.getBuildTarget();
    buildTarget = LinkerMapMode.buildTargetByAddingDefaultLinkerMapFlavorIfNeeded(buildTarget);
    return LinkerMapMode.FLAVOR_DOMAIN.getRequiredValue(buildTarget).getFlavor();
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return getLibFilePath();
  }

  @Override
  public RuleScheduleInfo getRuleScheduleInfo() {
    return cxxBuckConfig.getLinkScheduleInfo().orElse(RuleScheduleInfo.DEFAULT);
  }

  private Path getScratchPath() {
    // ld doesn't seem to like commas in the version script path so we construct one without commas.
    Path path = BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s");
    String dirname = path.getFileName().toString().replace(",", ".");
    return path.getParent().resolve(dirname);
  }


  private Path getBaseLibPath() {
    return getResolver().getAbsolutePath(baseLibSourcePath);
  }

  private Path getScratchDirPath() {
    return getScratchPath().resolve(cpuType.toString());
  }

  private Path getScratchFilePath(String suffix) {
    return getScratchDirPath().resolve(
        MorePaths.getNameWithoutExtension(getBaseLibPath()) + suffix);
  }

  private Path getLibFilePath() {
    return getScratchDirPath().resolve(getBaseLibPath().getFileName());
  }

  private Symbols getSymbols(ProcessExecutor executor, Path path)
      throws IOException, InterruptedException {
    return Symbols.getSymbols(
        executor,
        objdump,
        getResolver(),
        absolutify(path));
  }

  private Path getRelativeVersionFilePath() {
    return getScratchFilePath("__version.exp");
  }

  private void writeVersionScript(ProcessExecutor executor, ImmutableSet<String> symbolsNeeded)
      throws IOException, InterruptedException {
    Symbols sym = getSymbols(executor, getBaseLibPath());
    Set<String> defined = Sets.difference(sym.all, sym.undefined);
    String versionScript = getVersionScript(symbolsNeeded, defined);

    Files.write(
        absolutify(getRelativeVersionFilePath()),
        versionScript.getBytes(Charsets.UTF_8),
        StandardOpenOption.CREATE);
  }

  private Path absolutify(Path p) {
    return getProjectFilesystem().resolve(p);
  }

  private Path getSymbolsNeededOutPath() {
    return getScratchFilePath(".symbols");
  }

  private void writeSymbols(Path dest, Set<String> symbols) throws IOException {
    Files.write(
        absolutify(dest),
        ImmutableSortedSet.copyOf(symbols),
        Charsets.UTF_8,
        StandardOpenOption.CREATE);
  }

  private ImmutableSet<String> readSymbolsNeeded() throws IOException {
    ImmutableSet.Builder<String> symbolsNeeded = ImmutableSet.builder();
    for (SourcePath source : symbolsNeededPaths) {
      symbolsNeeded.addAll(
          Files.readAllLines(getResolver().getAbsolutePath(source), Charsets.UTF_8));
    }
    return symbolsNeeded.build();
  }
}
