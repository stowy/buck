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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

public class CxxBoostTestTest {

  private static final ObjectMapper mapper = ObjectMappers.newDefaultInstance();
  private static final TypeReference<List<TestResultSummary>> SUMMARIES_REFERENCE =
      new TypeReference<List<TestResultSummary>>() {};

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testParseResults() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "boost_test", tmp);
    workspace.setUp();

    ImmutableList<String> samples =
        ImmutableList.of(
            "simple_success",
            "simple_failure",
            "simple_failure_with_output");

    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    CxxBoostTest test =
        new CxxBoostTest(
            new FakeBuildRuleParamsBuilder(target)
                .setProjectFilesystem(new ProjectFilesystem(tmp.getRoot()))
                .build(),
            pathResolver,
            new CxxLink(
                new FakeBuildRuleParamsBuilder(
                    BuildTargetFactory.newInstance("//:link")
                        .withFlavors(LinkerMapMode.DEFAULT_MODE.getFlavor()))
                    .build(),
                pathResolver,
                CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(ruleResolver),
                Paths.get("output"),
                ImmutableList.of(),
                Optional.empty(),
                /* cacheable */ true),
            new CommandTool.Builder()
                .addArg(new StringArg(""))
                .build(),
            Suppliers.ofInstance(ImmutableMap.of()),
            Suppliers.ofInstance(ImmutableList.of()),
            ImmutableSortedSet.of(),
            Suppliers.ofInstance(ImmutableSortedSet.of()),
            ImmutableSet.of(),
            ImmutableSet.of(),
            /* runTestSeparately */ false,
            /* testRuleTimeoutMs */ Optional.empty());

    for (String sample : samples) {
      Path exitCode = Paths.get("unused");
      Path output = workspace.resolve(Paths.get(sample)).resolve("output");
      Path results = workspace.resolve(Paths.get(sample)).resolve("results");
      Path summaries = workspace.resolve(Paths.get(sample)).resolve("summaries");
      List<TestResultSummary> expectedSummaries =
          mapper.readValue(summaries.toFile(), SUMMARIES_REFERENCE);
      ImmutableList<TestResultSummary> actualSummaries =
          test.parseResults(exitCode, output, results);
      assertEquals(sample, expectedSummaries, actualSummaries);
    }

  }

}
