package org.apache.maven.plugins.dependency.analyze;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzer;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzerException;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;

/**
 * Analyzes the dependencies of this project and determines which are: used and declared; used and undeclared; unused
 * and declared.
 *
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 * @since 2.0-alpha-5
 */
public abstract class AbstractAnalyzeMojo
    extends AbstractMojo
    implements Contextualizable
{
    // fields -----------------------------------------------------------------

    /**
     * The plexus context to look-up the right {@link ProjectDependencyAnalyzer} implementation depending on the mojo
     * configuration.
     */
    private Context context;

    /**
     * The Maven project to analyze.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    /**
     * Specify the project dependency analyzer to use (plexus component role-hint). By default,
     * <a href="/shared/maven-dependency-analyzer/">maven-dependency-analyzer</a> is used. To use this, you must declare
     * a dependency for this plugin that contains the code for the analyzer. The analyzer must have a declared Plexus
     * role name, and you specify the role name here.
     *
     * @since 2.2
     */
    @Parameter( property = "analyzer", defaultValue = "default" )
    private String analyzer;

    /**
     * Whether to fail the build if a dependency warning is found.
     */
    @Parameter( property = "failOnWarning", defaultValue = "false" )
    private boolean failOnWarning;

    /**
     * Output used dependencies.
     */
    @Parameter( property = "verbose", defaultValue = "false" )
    private boolean verbose;

    /**
     * Ignore Runtime/Provided/Test/System scopes for unused dependency analysis.
     */
    @Parameter( property = "ignoreNonCompile", defaultValue = "false" )
    private boolean ignoreNonCompile;

    /**
     * Output the xml for the missing dependencies (used but not declared).
     *
     * @since 2.0-alpha-5
     */
    @Parameter( property = "outputXML", defaultValue = "false" )
    private boolean outputXML;

    /**
     * Output the xml for the missing dependencies (used but not declared).
     */
    @Parameter( property = "outputJSON", defaultValue = "false" )
    private boolean outputJSON;

    /**
     * Output scriptable values for the missing dependencies (used but not declared).
     *
     * @since 2.0-alpha-5
     */
    @Parameter( property = "scriptableOutput", defaultValue = "false" )
    private boolean scriptableOutput;

    /**
     * Flag to use for scriptable output.
     *
     * @since 2.0-alpha-5
     */
    @Parameter( property = "scriptableFlag", defaultValue = "$$$%%%" )
    private String scriptableFlag;

    /**
     * Flag to use for scriptable output
     *
     * @since 2.0-alpha-5
     */
    @Parameter( defaultValue = "${basedir}", readonly = true )
    private File baseDir;

    /**
     * Target folder
     *
     * @since 2.0-alpha-5
     */
    @Parameter( defaultValue = "${project.build.directory}", readonly = true )
    private File outputDirectory;

    /**
     * Force dependencies as used, to override incomplete result caused by bytecode-level analysis. Dependency format is
     * <code>groupId:artifactId</code>.
     *
     * @since 2.6
     */
    @Parameter
    private String[] usedDependencies;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter( property = "mdep.analyze.skip", defaultValue = "false" )
    private boolean skip;

    /**
     * List of dependencies that will be ignored. Any dependency on this list will be excluded from the "declared but
     * unused" and the "used but undeclared" list. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private String[] ignoredDependencies = new String[0];

    /**
     * List of dependencies that will be ignored if they are used but undeclared. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private String[] ignoredUsedUndeclaredDependencies = new String[0];

    /**
     * List of dependencies that will be ignored if they are declared but unused. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private String[] ignoredUnusedDeclaredDependencies = new String[0];

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( isSkip() )
        {
            getLog().info( "Skipping plugin execution" );
            return;
        }

        if ( "pom".equals( project.getPackaging() ) )
        {
            getLog().info( "Skipping pom project" );
            return;
        }

        if ( outputDirectory == null || !outputDirectory.exists() )
        {
            getLog().info( "Skipping project with no build directory" );
            return;
        }

        boolean warning = checkDependencies();

        if ( warning && failOnWarning )
        {
            throw new MojoExecutionException( "Dependency problems found" );
        }
    }

    /**
     * @return {@link ProjectDependencyAnalyzer}
     * @throws MojoExecutionException in case of an error.
     */
    protected ProjectDependencyAnalyzer createProjectDependencyAnalyzer()
        throws MojoExecutionException
    {

        final String role = ProjectDependencyAnalyzer.ROLE;
        final String roleHint = analyzer;

        try
        {
            final PlexusContainer container = (PlexusContainer) context.get( PlexusConstants.PLEXUS_KEY );

            return (ProjectDependencyAnalyzer) container.lookup( role, roleHint );
        }
        catch ( Exception exception )
        {
            throw new MojoExecutionException( "Failed to instantiate ProjectDependencyAnalyser with role " + role
                + " / role-hint " + roleHint, exception );
        }
    }

    @Override
    public void contextualize( Context theContext )
        throws ContextException
    {
        this.context = theContext;
    }

    /**
     * @return {@link #skip}
     */
    protected final boolean isSkip()
    {
        return skip;
    }

    // private methods --------------------------------------------------------

    private boolean checkDependencies()
        throws MojoExecutionException
    {
        ProjectDependencyAnalysis analysis;
        try
        {
            analysis = createProjectDependencyAnalyzer().analyze( project );

            if ( usedDependencies != null )
            {
                analysis = analysis.forceDeclaredDependenciesUsage( usedDependencies );
            }
        }
        catch ( ProjectDependencyAnalyzerException exception )
        {
            throw new MojoExecutionException( "Cannot analyze dependencies", exception );
        }

        if ( ignoreNonCompile )
        {
            analysis = analysis.ignoreNonCompile();
        }

        Set<Artifact> usedDeclared = new LinkedHashSet<>( analysis.getUsedDeclaredArtifacts() );
        Set<Artifact> usedUndeclared = new LinkedHashSet<>( analysis.getUsedUndeclaredArtifacts() );
        Set<Artifact> unusedDeclared = new LinkedHashSet<>( analysis.getUnusedDeclaredArtifacts() );

        Set<Artifact> ignoredUsedUndeclared = new LinkedHashSet<>();
        Set<Artifact> ignoredUnusedDeclared = new LinkedHashSet<>();

        ignoredUsedUndeclared.addAll( filterDependencies( usedUndeclared, ignoredDependencies ) );
        ignoredUsedUndeclared.addAll( filterDependencies( usedUndeclared, ignoredUsedUndeclaredDependencies ) );

        ignoredUnusedDeclared.addAll( filterDependencies( unusedDeclared, ignoredDependencies ) );
        ignoredUnusedDeclared.addAll( filterDependencies( unusedDeclared, ignoredUnusedDeclaredDependencies ) );

        boolean reported = false;
        boolean warning = false;

        if ( verbose && !usedDeclared.isEmpty() )
        {
            getLog().info( "Used declared dependencies found:" );

            logArtifacts( analysis.getUsedDeclaredArtifacts(), false );
            reported = true;
        }

        if ( !usedUndeclared.isEmpty() )
        {
            getLog().warn( "Used undeclared dependencies found:" );

            logArtifacts( usedUndeclared, true );
            reported = true;
            warning = true;
        }

        if ( !unusedDeclared.isEmpty() )
        {
            getLog().warn( "Unused declared dependencies found:" );

            logArtifacts( unusedDeclared, true );
            reported = true;
            warning = true;
        }

        if ( verbose && !ignoredUsedUndeclared.isEmpty() )
        {
            getLog().info( "Ignored used undeclared dependencies:" );

            logArtifacts( ignoredUsedUndeclared, false );
            reported = true;
        }

        if ( verbose && !ignoredUnusedDeclared.isEmpty() )
        {
            getLog().info( "Ignored unused declared dependencies:" );

            logArtifacts( ignoredUnusedDeclared, false );
            reported = true;
        }

        if ( outputXML )
        {
            writeDependencyXML( usedUndeclared );
        }

        if ( outputJSON )
        {
            writeDependencyJSON( usedUndeclared, unusedDeclared );
        }

        if ( scriptableOutput )
        {
            writeScriptableOutput( usedUndeclared );
        }

        if ( !reported )
        {
            getLog().info( "No dependency problems found" );
        }

        return warning;
    }

    private void logArtifacts( Set<Artifact> artifacts, boolean warn )
    {
        if ( artifacts.isEmpty() )
        {
            getLog().info( "   None" );
        }
        else
        {
            for ( Artifact artifact : artifacts )
            {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                if ( warn )
                {
                    getLog().warn( "   " + artifact );
                }
                else
                {
                    getLog().info( "   " + artifact );
                }

            }
        }
    }

    private void writeDependencyXML( Set<Artifact> artifacts )
    {
        if ( !artifacts.isEmpty() )
        {
            getLog().info( "Add the following to your pom to correct the missing dependencies: " );

            StringWriter out = new StringWriter();
            PrettyPrintXMLWriter writer = new PrettyPrintXMLWriter( out );

            for ( Artifact artifact : artifacts )
            {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                writer.startElement( "dependency" );
                writer.startElement( "groupId" );
                writer.writeText( artifact.getGroupId() );
                writer.endElement();
                writer.startElement( "artifactId" );
                writer.writeText( artifact.getArtifactId() );
                writer.endElement();
                writer.startElement( "version" );
                writer.writeText( artifact.getBaseVersion() );
                if ( !StringUtils.isBlank( artifact.getClassifier() ) )
                {
                    writer.startElement( "classifier" );
                    writer.writeText( artifact.getClassifier() );
                    writer.endElement();
                }
                writer.endElement();

                if ( !Artifact.SCOPE_COMPILE.equals( artifact.getScope() ) )
                {
                    writer.startElement( "scope" );
                    writer.writeText( artifact.getScope() );
                    writer.endElement();
                }
                writer.endElement();
            }

            getLog().info( System.lineSeparator() + out.getBuffer() );
        }
    }

    private String joinArtifacts( List<Artifact> artifacts )
    {
        StringBuffer sb = new StringBuffer();
        for ( int i = 0; i < artifacts.size(); i++ )
        {
            if ( i != 0 )
            {
                sb.append( ", " );
            }
            Artifact artifact = artifacts.get( i );
            sb.append( artifact.getGroupId() + ":" + artifact.getArtifactId() );
        }
        return sb.toString();
    }

    private void writeDependencyJSON( Set<Artifact> usedUndeclared, Set<Artifact> unusedDeclared ) 
    {
        if ( !usedUndeclared.isEmpty() || !unusedDeclared.isEmpty() )
        {
            StringBuilder buf = new StringBuilder();

            buf.append( "{dependencyIssues:\"true\", " );
            buf.append( "originModule: \"" + project.getGroupId() + ":" + project.getArtifactId() + "\", " );
            if ( !usedUndeclared.isEmpty() )
            {
                buf.append( "usedUndeclared: [" );
                buf.append( joinArtifacts( new ArrayList<>( usedUndeclared ) ) );
                buf.append( "]" );
            }
            if ( !unusedDeclared.isEmpty() )
            {
                if ( !usedUndeclared.isEmpty() )
                {
                    buf.append( ", " );
                }
                buf.append( "unusedDeclared: [" );
                buf.append( joinArtifacts( new ArrayList<>( unusedDeclared ) ) );
                buf.append( "]" );
            }
            buf.append( "}" );
            getLog().warn( buf.toString() );
        }
    }

    private void writeScriptableOutput( Set<Artifact> artifacts )
    {
        if ( !artifacts.isEmpty() )
        {
            getLog().info( "Missing dependencies: " );
            String pomFile = baseDir.getAbsolutePath() + File.separatorChar + "pom.xml";
            StringBuilder buf = new StringBuilder();

            for ( Artifact artifact : artifacts )
            {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                //CHECKSTYLE_OFF: LineLength
                buf.append( scriptableFlag )
                   .append( ":" )
                   .append( pomFile )
                   .append( ":" )
                   .append( artifact.getDependencyConflictId() )
                   .append( ":" )
                   .append( artifact.getClassifier() )
                   .append( ":" )
                   .append( artifact.getBaseVersion() )
                   .append( ":" )
                   .append( artifact.getScope() )
                   .append( System.lineSeparator() );
                //CHECKSTYLE_ON: LineLength
            }
            getLog().info( System.lineSeparator() + buf );
        }
    }

    private List<Artifact> filterDependencies( Set<Artifact> artifacts, String[] excludes )
        throws MojoExecutionException
    {
        ArtifactFilter filter = new StrictPatternExcludesArtifactFilter( Arrays.asList( excludes ) );
        List<Artifact> result = new ArrayList<>();

        for ( Iterator<Artifact> it = artifacts.iterator(); it.hasNext(); )
        {
            Artifact artifact = it.next();
            if ( !filter.include( artifact ) )
            {
                it.remove();
                result.add( artifact );
            }
        }

        return result;
    }
}
