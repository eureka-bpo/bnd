package aQute.bnd.maven.testing.plugin;

import static aQute.bnd.maven.lib.resolve.BndrunContainer.report;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import aQute.bnd.maven.lib.configuration.Bndruns;
import aQute.bnd.maven.lib.configuration.Bundles;
import aQute.bnd.maven.lib.resolve.BndrunContainer;
import aQute.bnd.maven.lib.resolve.Operation;
import aQute.bnd.maven.lib.resolve.Scope;
import aQute.bnd.osgi.Constants;
import aQute.bnd.unmodifiable.Sets;
import aQute.lib.strings.Strings;
import aQute.libg.glob.Glob;
import biz.aQute.resolve.ResolveProcess;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.settings.Settings;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.osgi.service.resolver.ResolutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mojo(name = "testing", defaultPhase = LifecyclePhase.INTEGRATION_TEST, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class TestingMojo extends AbstractMojo {
	private static final Logger									logger	= LoggerFactory.getLogger(TestingMojo.class);

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject										project;

	@Parameter(defaultValue = "${settings}", readonly = true)
	private Settings											settings;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
	private RepositorySystemSession								repositorySession;

	@Parameter(property = "skipTests", defaultValue = "false")
	private boolean												skipTests;

	@Parameter(property = "maven.test.skip", defaultValue = "false")
	private boolean												skip;

	@Parameter(property = "bnd.testing.failure.ignore", defaultValue = "false")
	private boolean												testFailureIgnore;

	@Parameter
	private Bndruns												bndruns	= new Bndruns();

	@Parameter(defaultValue = "${project.build.directory}/test", readonly = true)
	private File												cwd;

	@Parameter(defaultValue = "${project.build.directory}/test-reports", readonly = true)
	private File												reportsDir;

	@Parameter(defaultValue = "${testing.select}", readonly = true)
	private File												testingSelect;

	@Parameter(defaultValue = "${testing}", readonly = true)
	private String												testing;

	@Parameter(property = "test")
	private String												test;

	@Parameter(required = false)
	private Bundles												bundles	= new Bundles();

	@Parameter(defaultValue = "true")
	private boolean												useMavenDependencies;

	@Parameter(defaultValue = "false")
	private boolean												resolve;

	@Parameter(defaultValue = "true")
	private boolean												reportOptional;

	@Parameter(defaultValue = "true")
	private boolean												failOnChanges;

	@Parameter(defaultValue = "${session}", readonly = true)
	private MavenSession										session;

	@Parameter(property = "bnd.testing.scopes", defaultValue = "compile,runtime")
	private Set<Scope>											scopes	= Sets.of(Scope.compile, Scope.runtime);

	@Parameter(property = "bnd.testing.include.dependency.management", defaultValue = "false")
	private boolean												includeDependencyManagement;

	@Parameter(defaultValue = "${project.build.directory}", readonly = true)
	private File												targetDir;

	/**
	 * The bndrun files will be read from this directory.
	 */
	@Parameter(defaultValue = "${project.basedir}")
	private File 												bndrunDir;

	@Component
	private RepositorySystem									system;

	@Component
	private ProjectDependenciesResolver							resolver;

	@Component
	@SuppressWarnings("deprecation")
	private org.apache.maven.artifact.factory.ArtifactFactory	artifactFactory;

	@Component
	private ToolchainManager									toolchainManager;

	private Glob												glob	= new Glob("*");

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip || skipTests) {
			logger.debug("skip project as configured");
			return;
		}

		if (testingSelect != null) {
			logger.info("Using selected testing file {}", testingSelect);
			bndruns = new Bndruns();
			bndruns.setBndrun(testingSelect);
		} else {
			glob = new Glob(testing == null ? "*" : testing);
			logger.info("Matching glob {}", glob);
		}

		int errors = 0;

		try {
			List<File> bndrunFiles = bndruns.getFiles(bndrunDir, "*.bndrun");

			if (bndrunFiles.isEmpty()) {
				logger.warn(
					"No bndrun files were specified with <bndrun> or found as *.bndrun in the project. This is unexpected.");
				return;
			}

			BndrunContainer container = new BndrunContainer.Builder(project, session, repositorySession, resolver,
				artifactFactory, system).setBundles(bundles.getFiles(project.getBasedir()))
					.setIncludeDependencyManagement(includeDependencyManagement)
					.setScopes(scopes)
					.setUseMavenDependencies(useMavenDependencies)
					.build();

			Operation operation = getOperation();

			for (File runFile : bndrunFiles) {
				logger.info("Testing {}:", runFile);
				errors += container.execute(runFile, "testing", cwd, operation);
			}
		} catch (Exception e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}

		if (!isTestFailureIgnore() && (errors > 0)) {
			throw new MojoFailureException(errors + " errors found");
		}
	}

	private List<String> getTests() {
		logger.debug("getTests: {}", test);
		if (test == null || test.trim()
			.isEmpty()) {
			return null;
		}
		return Strings.split(test);
	}

	private boolean isTestFailureIgnore() {
		return testFailureIgnore;
	}

	private Operation getOperation() {
		return (file, bndrun, run) -> {
			if (!glob.matcher(file.getName())
				.matches()) {
				logger.info("Skipping {}", bndrun);
				return 0;
			}
			if (resolve) {
				try {
					String runBundles = run.resolve(failOnChanges, false);
					if (run.isOk()) {
						logger.info("{}: {}", Constants.RUNBUNDLES, runBundles);
						run.setProperty(Constants.RUNBUNDLES, runBundles);
					}
				} catch (ResolutionException re) {
					logger.error(ResolveProcess.format(re, reportOptional));
					throw re;
				} finally {
					int errors = report(run);
					if (errors > 0) {
						return errors;
					}
				}
			}
			getToolchainJavaHome().ifPresent(home -> run.setProperty("java", new StringBuilder().append(home)
				.append(File.separatorChar)
				.append("bin")
				.append(File.separatorChar)
				.append("java")
				.toString()));
			try {
				run.test(new File(reportsDir, bndrun), getTests());
			} finally {
				int errors = report(run);
				if (errors > 0) {
					return errors;
				}
			}

			return 0;
		};
	}

	@SuppressWarnings("deprecation")
	private Optional<String> getToolchainJavaHome() {
		return Optional.ofNullable(toolchainManager)
			.map(tm -> tm.getToolchainFromBuildContext("jdk", session))
			.filter(org.apache.maven.toolchain.java.DefaultJavaToolChain.class::isInstance)
			.map(org.apache.maven.toolchain.java.DefaultJavaToolChain.class::cast)
			.map(org.apache.maven.toolchain.java.DefaultJavaToolChain::getJavaHome);
	}

}
