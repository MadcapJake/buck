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

package com.facebook.buck.jvm.java;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.regex.Pattern;


public class JavaTestDescription implements
    Description<JavaTestDescription.Arg>,
    ImplicitDepsInferringDescription<JavaTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("java_test");

  private final JavaOptions javaOptions;
  private final JavacOptions templateJavacOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public JavaTestDescription(
      JavaOptions javaOptions,
      JavacOptions templateOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.javaOptions = javaOptions;
    this.templateJavacOptions = templateOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
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
  public <A extends Arg> JavaTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            templateJavacOptions,
            params,
            resolver,
            pathResolver,
            args
        );

    CxxLibraryEnhancement cxxLibraryEnhancement = new CxxLibraryEnhancement(
        params,
        args.useCxxLibraries,
        args.cxxLibraryWhitelist,
        resolver,
        pathResolver,
        cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    BuildTarget abiJarTarget = params.getBuildTarget().withAppendedFlavors(CalculateAbi.FLAVOR);

    BuildRuleParams testsLibraryParams = params.copyWithDeps(
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(params.getDeclaredDeps())
            .addAll(BuildRules.getExportedRules(
                Iterables.concat(
                    params.getDeclaredDeps(),
                    resolver.getAllRules(args.providedDeps.get()))))
            .addAll(pathResolver.filterBuildRuleInputs(
                javacOptions.getInputs(pathResolver)))
            .build(),
        params.getExtraDeps())
        .withFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    JavaLibrary testsLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                testsLibraryParams,
                pathResolver,
                args.srcs.get(),
                ResourceValidator.validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources.get()),
                javacOptions.getGeneratedSourceFolderName(),
                args.proguardConfig.transform(
                    SourcePaths.toSourcePath(params.getProjectFilesystem())),
                /* postprocessClassesCommands */ ImmutableList.<String>of(),
                /* exportDeps */ ImmutableSortedSet.<BuildRule>of(),
                /* providedDeps */ ImmutableSortedSet.<BuildRule>of(),
                new BuildTargetSourcePath(abiJarTarget),
                javacOptions.trackClassUsage(),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                new JavacToJarStepFactory(javacOptions, JavacOptionsAmender.IDENTITY),
                args.resourcesRoot,
                args.manifestFile,
                args.mavenCoords,
                /* tests */ ImmutableSortedSet.<BuildTarget>of(),
                /* classesToRemoveFromJar */ ImmutableSet.<Pattern>of()
            ));

  JavaTest javaTest =
      resolver.addToIndex(
          new JavaTest(
              params.copyWithDeps(
                  ImmutableSortedSet.<BuildRule>of(testsLibrary),
                  ImmutableSortedSet.<BuildRule>of()),
              pathResolver,
              testsLibrary,
              /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
              args.labels.get(),
              args.contacts.get(),
              args.testType.or(TestType.JUNIT),
              javaOptions.getJavaRuntimeLauncher(),
              args.vmArgs.get(),
              cxxLibraryEnhancement.nativeLibsEnvironment,
              args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
              args.env.get(),
              args.getRunTestSeparately(),
              args.getForkMode(),
              args.stdOutLogLevel,
              args.stdErrLogLevel));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(testsLibrary.getBuildTarget())));

    return javaTest;
  }

  public static ImmutableSet<BuildRule> validateAndGetSourcesUnderTest(
      ImmutableSet<BuildTarget> sourceUnderTestTargets,
      BuildTarget owner,
      BuildRuleResolver resolver) {
    ImmutableSet.Builder<BuildRule> sourceUnderTest = ImmutableSet.builder();
    for (BuildTarget target : sourceUnderTestTargets) {
      BuildRule rule = resolver.getRule(target);
      if (!(rule instanceof JavaLibrary)) {
        // In this case, the source under test specified in the build file was not a Java library
        // rule. Since EMMA requires the sources to be in Java, we will throw this exception and
        // not continue with the tests.
        throw new HumanReadableException(
            "Specified source under test for %s is not a Java library: %s (%s).",
            owner,
            rule.getFullyQualifiedName(),
            rule.getType());
      }
      sourceUnderTest.add(rule);
    }
    return sourceUnderTest.build();
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
    if (constructorArg.useCxxLibraries.or(false)) {
      deps.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform));
    }
    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<ImmutableList<String>> vmArgs;
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<ForkMode> forkMode;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<Boolean> useCxxLibraries;
    public Optional<ImmutableSet<BuildTarget>> cxxLibraryWhitelist;
    public Optional<Long> testRuleTimeoutMs;
    public Optional<ImmutableMap<String, String>> env;

    public boolean getRunTestSeparately() {
      return runTestSeparately.or(false);
    }
    public ForkMode getForkMode() {
      return forkMode.or(ForkMode.NONE);
    }
  }

  public static class CxxLibraryEnhancement {
    public final BuildRuleParams updatedParams;
    public final ImmutableMap<String, String> nativeLibsEnvironment;

    public CxxLibraryEnhancement(
        BuildRuleParams params,
        Optional<Boolean> useCxxLibraries,
        final Optional<ImmutableSet<BuildTarget>> cxxLibraryWhitelist,
        BuildRuleResolver resolver,
        SourcePathResolver pathResolver,
        CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
      if (useCxxLibraries.or(false)) {
        SymlinkTree nativeLibsSymlinkTree =
            buildNativeLibsSymlinkTreeRule(params, pathResolver, cxxPlatform);
        Predicate<BuildRule> shouldInclude = Predicates.alwaysTrue();
        if (cxxLibraryWhitelist.isPresent() && !cxxLibraryWhitelist.get().isEmpty()) {
          shouldInclude = new Predicate<BuildRule>() {
            @Override
            public boolean apply(BuildRule input) {
              return cxxLibraryWhitelist.get().contains(
                  input.getBuildTarget().withFlavors());
            }
          };
        }
        updatedParams = params.appendExtraDeps(ImmutableList.<BuildRule>builder()
            .add(nativeLibsSymlinkTree)
            // Add all the native libraries as first-order dependencies.
            // This has two effects:
            // (1) They become runtime deps because JavaTest adds all first-order deps.
            // (2) They affect the JavaTest's RuleKey, so changing them will invalidate
            // the test results cache.
            .addAll(
                FluentIterable.from(
                    pathResolver.filterBuildRuleInputs(nativeLibsSymlinkTree.getLinks().values()))
                    .filter(shouldInclude))
            .build());
        nativeLibsEnvironment =
            ImmutableMap.of(
                cxxPlatform.getLd().resolve(resolver).searchPathEnvVar(),
                nativeLibsSymlinkTree.getRoot().toString());
      } else {
        updatedParams = params;
        nativeLibsEnvironment = ImmutableMap.of();
      }
    }

    public static SymlinkTree buildNativeLibsSymlinkTreeRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver pathResolver,
        CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
      return CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
          buildRuleParams,
          pathResolver,
          cxxPlatform,
          buildRuleParams.getDeps(),
          Predicates.or(
              Predicates.instanceOf(NativeLinkable.class),
              Predicates.instanceOf(JavaLibrary.class)));
    }
  }
}
