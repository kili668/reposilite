/*
 * Copyright (c) 2021 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reposilite.maven

import com.reposilite.maven.api.DeleteRequest
import com.reposilite.maven.api.DeployRequest
import com.reposilite.maven.api.DocumentInfo
import com.reposilite.maven.api.FileDetails
import com.reposilite.maven.api.LookupRequest
import com.reposilite.shared.getSimpleName
import com.reposilite.shared.toNormalizedPath
import com.reposilite.shared.toPath
import com.reposilite.web.error.ErrorResponse
import com.reposilite.web.error.errorResponse
import io.javalin.http.HttpCode
import io.javalin.http.HttpCode.BAD_REQUEST
import io.javalin.http.HttpCode.INSUFFICIENT_STORAGE
import io.javalin.http.HttpCode.NOT_FOUND
import io.javalin.http.HttpCode.UNAUTHORIZED
import net.dzikoysk.dynamiclogger.Journalist
import net.dzikoysk.dynamiclogger.Logger
import panda.std.Result
import java.nio.file.Path
import java.nio.file.Paths

class MavenFacade internal constructor(
    private val journalist: Journalist,
    private val metadataService: MetadataService,
    private val repositorySecurityProvider: RepositorySecurityProvider,
    private val repositoryService: RepositoryService,
    private val proxyClient: ProxyClient
) : Journalist {

    companion object {
        val REPOSITORIES: Path = Paths.get("repositories")
    }

    suspend fun findFile(lookupRequest: LookupRequest): Result<out FileDetails, ErrorResponse> {
        val repository = repositoryService.getRepository(lookupRequest.repository) ?: return errorResponse(NOT_FOUND, "Repository not found")
        val gav = lookupRequest.gav.toPath()

        if (repositorySecurityProvider.canAccessResource(lookupRequest.accessToken, repository, gav).not()) {
            return errorResponse(UNAUTHORIZED, "Unauthorized access request")
        }

        if (repository.exists(gav).not()) {
            return proxyClient.findFile(repository, lookupRequest.gav)
        }

        if (repository.isDirectory(gav) && repositorySecurityProvider.canBrowseResource(lookupRequest.accessToken, repository, gav).not()) {
            return errorResponse(UNAUTHORIZED, "Unauthorized indexing request")
        }

        return repository.getFileDetails(gav)
    }

    fun deployFile(deployRequest: DeployRequest): Result<DocumentInfo, ErrorResponse> {
        val repository = repositoryService.getRepository(deployRequest.repository) ?: return errorResponse(NOT_FOUND, "Repository not found")
        val path = deployRequest.gav.toNormalizedPath().orNull() ?: return errorResponse(BAD_REQUEST, "Invalid GAV")

        if (repository.redeployment.not() && path.getSimpleName().contains(METADATA_FILE_NAME).not() && repository.exists(path)) {
            return errorResponse(HttpCode.CONFLICT, "Redeployment is not allowed")
        }

        if (repository.isFull()) {
            return errorResponse(INSUFFICIENT_STORAGE, "Not enough storage space available")
        }

        return repository.putFile(path, deployRequest.content)
            .peek { logger.info("DEPLOY Artifact successfully deployed $path by ${deployRequest.by}") }
    }

    fun deleteFile(deleteRequest: DeleteRequest): Result<*, ErrorResponse> {
        val repository = repositoryService.getRepository(deleteRequest.repository) ?: return errorResponse<Any>(NOT_FOUND, "Repository ${deleteRequest.repository} not found")
        val path = deleteRequest.gav.toNormalizedPath().orNull() ?: return errorResponse<Any>(NOT_FOUND, "Invalid GAV")

        if (repositorySecurityProvider.canModifyResource(deleteRequest.accessToken, repository, path).not()) {
            return errorResponse<Any>(UNAUTHORIZED, "Unauthorized access request")
        }

        return repository.removeFile(path)
    }

    fun getRepositories(): Collection<Repository> =
        repositoryService.getRepositories()

    override fun getLogger(): Logger =
        journalist.logger

}

/*
    fun exists(context: ReposiliteContext): Boolean {
        val uri: String = context.uri
        val result = repositoryAuthenticator.authDefaultRepository(context.header, uri)

        if (result.isErr) {
            // Maven requests maven-metadata.xml file during deploy for snapshot releases without specifying credentials
            // https://github.com/dzikoysk/reposilite/issues/184
            return if (uri.contains("-SNAPSHOT") && uri.endsWith(METADATA_FILE)) {
                false
            } else false
        }

        val path = result.get().key

        // discard invalid requests (less than 'group/(artifact OR metadata)')
        if (path.nameCount < 2) {
            return false
        }

        val repository = result.get().value
        return repository.exists(path)
    }

    fun find(context: ReposiliteContext): Result<LookupResponse, ErrorResponse> {
        val uri: String = context.uri
        val result = repositoryAuthenticator.authDefaultRepository(context.header, uri)

        if (result.isErr) {
            // Maven requests maven-metadata.xml file during deploy for snapshot releases without specifying credentials
            // https://github.com/dzikoysk/reposilite/issues/184
            return if (uri.contains("-SNAPSHOT") && uri.endsWith(METADATA_FILE)) {
                ResponseUtils.error(HttpStatus.SC_NOT_FOUND, result.error.message)
            } else Result.error(result.error)
        }

        var path = result.get().key

        // discard invalid requests (less than 'group/(artifact OR metadata)')
        if (path!!.nameCount < 2) {
            return ResponseUtils.error(HttpStatus.SC_NON_AUTHORITATIVE_INFORMATION, "Missing artifact identifier")
        }

        val repository = result.get().value
        val requestedFileName = path.fileName.toString()

        if (requestedFileName == "maven-metadata.xml") {
            return repository.getFile(path).map { LookupResponse.of("text/xml", Arrays.toString(it)) }
        }

        // resolve requests for latest version of artifact
        if (requestedFileName.equals("latest", ignoreCase = true)) {
            val requestDirectory = path.parent
            val versions: Result<List<Path>, ErrorResponse> = toSortedVersions(repository, requestDirectory)

            if (versions.isErr) {
                return versions.map { null }
            }

            val version = versions.get().firstOrNull()
                ?: return ResponseUtils.error(HttpStatus.SC_NOT_FOUND, "Latest version not found")

            return Result.ok(LookupResponse.of("text/plain", version.fileName.toString()))
        }

        // resolve snapshot requests
        if (requestedFileName.contains("-SNAPSHOT")) {
            path = repositoryService.resolveSnapshot(repository, path)

            if (path == null) {
                return Result.error(ErrorResponse(HttpStatus.SC_NOT_FOUND, "Latest version not found"))
            }
        }

        if (repository.isDirectory(path)) {
            return ResponseUtils.error(HttpStatus.SC_NON_AUTHORITATIVE_INFORMATION, "Directory access")
        }

        val bytes = repository.getFile(path)

        if (bytes.isErr) {
            return bytes.map { null }
        }

        val fileDetailsResult = repository.getFileDetails(path)

        return if (fileDetailsResult.isOk) {
            val fileDetails = fileDetailsResult.get()

            if (context.method != "HEAD") {
                context.output { it.write(bytes.get()) }
            }

            context.logger.debug("RESOLVED $path; mime: ${fileDetails.contentType}; size: ${repository.getFileSize(path).get()}")
            Result.ok(LookupResponse.of(fileDetails))
        }
        else {
            Result.error(fileDetailsResult.error)
        }
    }

     */