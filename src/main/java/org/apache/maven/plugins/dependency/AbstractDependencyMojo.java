package org.apache.maven.plugins.dependency;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.dependency.utils.DependencySilentLog;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.components.io.filemappers.FileMapper;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.ReflectionUtils;
import org.codehaus.plexus.util.StringUtils;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 */
public abstract class AbstractDependencyMojo
    extends AbstractMojo
{
    /**
     * To look up Archiver/UnArchiver implementations
     */
    @Component
    private ArchiverManager archiverManager;


    /**
     * For IDE build support
     */
    @Component
    private BuildContext buildContext;

    /**
     * Skip plugin execution only during incremental builds (e.g. triggered from M2E).
     * 
     * @since 3.4.0
     * @see #skip
     */
    @Parameter( defaultValue = "false" )
    private boolean skipDuringIncrementalBuild;

    /**
     * <p>
     * will use the jvm chmod, this is available for user and all level group level will be ignored
     * </p>
     * <b>since 2.6 is on by default</b>
     * 
     * @since 2.5.1
     */
    @Parameter( property = "dependency.useJvmChmod", defaultValue = "true" )
    private boolean useJvmChmod = true;

    /**
     * ignore to set file permissions when unpacking a dependency
     * 
     * @since 2.7
     */
    @Parameter( property = "dependency.ignorePermissions", defaultValue = "false" )
    private boolean ignorePermissions;

    /**
     * POM
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    /**
     * Remote repositories which will be searched for artifacts.
     */
    @Parameter( defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true )
    private List<ArtifactRepository> remoteRepositories;

    /**
     * Remote repositories which will be searched for plugins.
     */
    @Parameter( defaultValue = "${project.pluginArtifactRepositories}", readonly = true, required = true )
    private List<ArtifactRepository> remotePluginRepositories;

    /**
     * Contains the full list of projects in the reactor.
     */
    @Parameter( defaultValue = "${reactorProjects}", readonly = true )
    protected List<MavenProject> reactorProjects;

    /**
     * The Maven session
     */
    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    protected MavenSession session;

    /**
     * If the plugin should be silent.
     *
     * @since 2.0
     */
    @Parameter( property = "silent", defaultValue = "false" )
    private boolean silent;

    /**
     * Output absolute filename for resolved artifacts
     *
     * @since 2.0
     */
    @Parameter( property = "outputAbsoluteArtifactFilename", defaultValue = "false" )
    protected boolean outputAbsoluteArtifactFilename;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter( property = "mdep.skip", defaultValue = "false" )
    private boolean skip;

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public final void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( isSkip() )
        {
            getLog().info( "Skipping plugin execution" );
            return;
        }

        doExecute();
    }

    /**
     * @throws MojoExecutionException {@link MojoExecutionException}
     * @throws MojoFailureException {@link MojoFailureException}
     */
    protected abstract void doExecute()
        throws MojoExecutionException, MojoFailureException;

    /**
     * @return Returns the archiverManager.
     */
    public ArchiverManager getArchiverManager()
    {
        return this.archiverManager;
    }

    /**
     * Does the actual copy of the file and logging.
     *
     * @param artifact represents the file to copy.
     * @param destFile file name of destination file.
     * @throws MojoExecutionException with a message if an error occurs.
     */
    protected void copyFile( File artifact, File destFile )
        throws MojoExecutionException
    {
        try
        {
            getLog().info( "Copying "
                + ( this.outputAbsoluteArtifactFilename ? artifact.getAbsolutePath() : artifact.getName() ) + " to "
                + destFile );

            if ( artifact.isDirectory() )
            {
                // usual case is a future jar packaging, but there are special cases: classifier and other packaging
                throw new MojoExecutionException( "Artifact has not been packaged yet. When used on reactor artifact, "
                    + "copy should be executed after packaging: see MDEP-187." );
            }

            FileUtils.copyFile( artifact, destFile );
            buildContext.refresh( destFile );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error copying artifact from " + artifact + " to " + destFile, e );
        }
    }

    /**
     * @param artifact {@link Artifact}
     * @param location The location.
     * @param encoding The encoding.
     * @param fileMappers {@link FileMapper}s to be used for rewriting each target path, or {@code null} if no rewriting
     *                    shall happen.
     * @throws MojoExecutionException in case of an error.
     */
    protected void unpack( Artifact artifact, File location, String encoding, FileMapper[] fileMappers )
        throws MojoExecutionException
    {
        unpack( artifact, location, null, null, encoding, fileMappers );
    }

    /**
     * Unpacks the archive file.
     *
     * @param artifact File to be unpacked.
     * @param location Location where to put the unpacked files.
     * @param includes Comma separated list of file patterns to include i.e. <code>**&#47;.xml,
     *                 **&#47;*.properties</code>
     * @param excludes Comma separated list of file patterns to exclude i.e. <code>**&#47;*.xml,
     *                 **&#47;*.properties</code>
     * @param encoding Encoding of artifact. Set {@code null} for default encoding.
     * @param fileMappers {@link FileMapper}s to be used for rewriting each target path, or {@code null} if no rewriting
     *                    shall happen.
     * @throws MojoExecutionException In case of errors.
     */
    protected void unpack( Artifact artifact, File location, String includes, String excludes, String encoding,
                           FileMapper[] fileMappers ) throws MojoExecutionException
    {
        unpack( artifact, artifact.getType(), location, includes, excludes, encoding, fileMappers );
    }

    /**
     * @param artifact {@link Artifact}
     * @param type The type.
     * @param location The location.
     * @param includes includes list.
     * @param excludes excludes list.
     * @param encoding the encoding.
     * @param fileMappers {@link FileMapper}s to be used for rewriting each target path, or {@code null} if no rewriting
     *                    shall happen.
     * @throws MojoExecutionException in case of an error.
     */
    protected void unpack( Artifact artifact, String type, File location, String includes, String excludes,
                           String encoding, FileMapper[] fileMappers )
        throws MojoExecutionException
    {
        File file = artifact.getFile();
        try
        {
            logUnpack( file, location, includes, excludes );

            location.mkdirs();
            if ( !location.exists() )
            {
                throw new MojoExecutionException( "Location to write unpacked files to could not be created: "
                    + location );
            }

            if ( file.isDirectory() )
            {
                // usual case is a future jar packaging, but there are special cases: classifier and other packaging
                throw new MojoExecutionException( "Artifact has not been packaged yet. When used on reactor artifact, "
                    + "unpack should be executed after packaging: see MDEP-98." );
            }

            UnArchiver unArchiver;

            try
            {
                unArchiver = archiverManager.getUnArchiver( type );
                getLog().debug( "Found unArchiver by type: " + unArchiver );
            }
            catch ( NoSuchArchiverException e )
            {
                unArchiver = archiverManager.getUnArchiver( file );
                getLog().debug( "Found unArchiver by extension: " + unArchiver );
            }

            if ( encoding != null && unArchiver instanceof ZipUnArchiver )
            {
                ( (ZipUnArchiver) unArchiver ).setEncoding( encoding );
                getLog().info( "Unpacks '" + type + "' with encoding '" + encoding + "'." );
            }

            unArchiver.setIgnorePermissions( ignorePermissions );

            unArchiver.setSourceFile( file );

            unArchiver.setDestDirectory( location );

            if ( StringUtils.isNotEmpty( excludes ) || StringUtils.isNotEmpty( includes ) )
            {
                // Create the selectors that will filter
                // based on include/exclude parameters
                // MDEP-47
                IncludeExcludeFileSelector[] selectors =
                    new IncludeExcludeFileSelector[] { new IncludeExcludeFileSelector() };

                if ( StringUtils.isNotEmpty( excludes ) )
                {
                    selectors[0].setExcludes( excludes.split( "," ) );
                }

                if ( StringUtils.isNotEmpty( includes ) )
                {
                    selectors[0].setIncludes( includes.split( "," ) );
                }

                unArchiver.setFileSelectors( selectors );
            }
            if ( this.silent )
            {
                silenceUnarchiver( unArchiver );
            }

            unArchiver.setFileMappers( fileMappers );

            unArchiver.extract();
        }
        catch ( NoSuchArchiverException e )
        {
            throw new MojoExecutionException( "Unknown archiver type", e );
        }
        catch ( ArchiverException e )
        {
            throw new MojoExecutionException( "Error unpacking file: " + file + " to: " + location, e );
        }
        buildContext.refresh( location );
    }

    private void silenceUnarchiver( UnArchiver unArchiver )
    {
        // dangerous but handle any errors. It's the only way to silence the unArchiver.
        try
        {
            Field field = ReflectionUtils.getFieldByNameIncludingSuperclasses( "logger", unArchiver.getClass() );

            field.setAccessible( true );

            field.set( unArchiver, this.getLog() );
        }
        catch ( Exception e )
        {
            // was a nice try. Don't bother logging because the log is silent.
        }
    }

    /**
     * @return Returns a new ProjectBuildingRequest populated from the current session and the current project remote
     *         repositories, used to resolve artifacts.
     */
    public ProjectBuildingRequest newResolveArtifactProjectBuildingRequest()
    {
        return newProjectBuildingRequest( remoteRepositories );
    }

    /**
     * @return Returns a new ProjectBuildingRequest populated from the current session and the current project remote
     *         repositories, used to resolve plugins.
     */
    protected ProjectBuildingRequest newResolvePluginProjectBuildingRequest()
    {
        return newProjectBuildingRequest( remotePluginRepositories );
    }

    private ProjectBuildingRequest newProjectBuildingRequest( List<ArtifactRepository> repositories )
    {
        ProjectBuildingRequest buildingRequest =
            new DefaultProjectBuildingRequest( session.getProjectBuildingRequest() );

        buildingRequest.setRemoteRepositories( repositories );

        return buildingRequest;
    }

    /**
     * @return Returns the project.
     */
    public MavenProject getProject()
    {
        return this.project;
    }

    /**
     * @param archiverManager The archiverManager to set.
     */
    public void setArchiverManager( ArchiverManager archiverManager )
    {
        this.archiverManager = archiverManager;
    }

    /**
     * @return {@link #useJvmChmod}
     */
    public boolean isUseJvmChmod()
    {
        return useJvmChmod;
    }

    /**
     * @param useJvmChmod {@link #useJvmChmod}
     */
    public void setUseJvmChmod( boolean useJvmChmod )
    {
        this.useJvmChmod = useJvmChmod;
    }

    /**
     * @return {@link #skip}
     */
    public boolean isSkip()
    {
        if ( skipDuringIncrementalBuild && buildContext.isIncremental() )
        {
            return true;
        }
        return skip;
    }

    /**
     * @param skip {@link #skip}
     */
    public void setSkip( boolean skip )
    {
        this.skip = skip;
    }

    /**
     * @return {@link #silent}
     */
    protected final boolean isSilent()
    {
        return silent;
    }

    /**
     * @param silent {@link #silent}
     */
    public void setSilent( boolean silent )
    {
        this.silent = silent;
        if ( silent )
        {
            setLog( new DependencySilentLog() );
        }
    }

    private void logUnpack( File file, File location, String includes, String excludes )
    {
        if ( !getLog().isInfoEnabled() )
        {
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append( "Unpacking " );
        msg.append( file );
        msg.append( " to " );
        msg.append( location );

        if ( includes != null && excludes != null )
        {
            msg.append( " with includes \"" );
            msg.append( includes );
            msg.append( "\" and excludes \"" );
            msg.append( excludes );
            msg.append( "\"" );
        }
        else if ( includes != null )
        {
            msg.append( " with includes \"" );
            msg.append( includes );
            msg.append( "\"" );
        }
        else if ( excludes != null )
        {
            msg.append( " with excludes \"" );
            msg.append( excludes );
            msg.append( "\"" );
        }

        getLog().info( msg.toString() );
    }
}
