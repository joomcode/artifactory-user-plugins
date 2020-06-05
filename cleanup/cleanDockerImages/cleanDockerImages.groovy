/*
 * Copyright (C) 2017 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Created by Madhu Reddy on 6/16/17.

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.artifactory.fs.ItemInfo
import org.artifactory.fs.StatsInfo
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.repo.Repositories
import org.joda.time.DateTime

import java.util.concurrent.TimeUnit

Repositories repositories

class ImageInfo {
    ItemInfo repo

    // Properties
    long expireDate // com.joom.retention.expireDate=YYYY-MM-DD

    // Labels
    Integer pullProtectDays // com.joom.retention.pullProtectDays=N
    Integer maxDays // com.joom.retention.maxDays=N
    Integer maxCount // com.joom.retention.maxCount=N
    String maxCountGroup // com.joom.retention.maxCountGroup

    // Image info
    long createdTime
}

// usage: curl -X POST http://localhost:8088/artifactory/api/plugins/execute/cleanDockerImages

executions {
    cleanDockerImages() { params ->
        def deleted = []
        def etcdir = ctx.artifactoryHome.etcDir
        def propsfile = new File(etcdir, "plugins/cleanDockerImages.properties")
        def parsed = new ConfigSlurper().parse(propsfile.toURL())
        def repos = parsed.dockerRepos
        def defaultMaxDays = parsed.defaultMaxDays
        def dryRun = params['dryRun'] ? params['dryRun'][0] as boolean : false
        repos.each {
            log.debug("Cleaning Docker images in repo: $it")
            def del = buildParentRepoPaths(RepoPathFactory.create(it), defaultMaxDays, dryRun)
            deleted.addAll(del)
        }
        def json = [status: 'okay', dryRun: dryRun, deleted: deleted]
        message = new JsonBuilder(json).toPrettyString()
        status = 200
    }
}

def buildParentRepoPaths(path, int defaultMaxDays, boolean dryRun) {
    return registryTraverse(repositories.getItemInfo(path), defaultMaxDays, dryRun)
}

// Traverse through the docker repo (directories and sub-directories) and:
// - delete the images immediately if the maxDays policy applies
// - Aggregate the images that qualify for maxCount policy (to get deleted in
//   the execution closure)

// - if docker image has `com.joom.retention.expireDate` property in the past - remove image;
// - if docker image has `com.joom.retention.expireDate` property in the future - keep image;
// - keep recently pulled images with `com.joom.retention.pullProtectDays` label (-1 - infinity time);
// - remove docker images if `com.joom.retention.maxDays` policy applices (-1 - infinity time);
// - aggregate the images for `com.joom.retention.maxCount` policy.
List<String> registryTraverse(ItemInfo parentInfo, int defaultMaxDays, boolean dryRun, Map<String, List<ImageInfo>> parentGroups = null, List<String> deleted = []) {
    List<RepoPath> removeSet = new ArrayList<>()
    List<ItemInfo> pendingQueue = new ArrayList<>()

    // Process current image
    RepoPath parentRepoPath = parentInfo.repoPath
    long now = new Date().time
    long oneDay = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)

    Map<String, RepoPath> layerPaths = new HashMap<>()
    ItemInfo manifestInfo
    for (ItemInfo childItem in repositories.getChildren(parentRepoPath)) {
        def currentPath = childItem.repoPath
        if (childItem.isFolder()) {
            pendingQueue << childItem
            continue
        }
        if (currentPath.name == "manifest.json") {
            manifestInfo = childItem
        }
        if (currentPath.name.startsWith("sha256__")) {
            layerPaths.put(currentPath.name, currentPath)
        }
    }
    while (manifestInfo != null) {
        log.debug("Scanning File: $manifestInfo.repoPath")
        ImageInfo info = getImageInfo(parentInfo, manifestInfo)
        if (info == null) {
            log.error("Invalid image labels: $parentRepoPath")
            break
        }
        if (info.expireDate > 0) {
            if (info.expireDate >= now) {
                log.warn("Removing image: $parentRepoPath - by expireDate")
                removeSet << parentRepoPath
                break
            }
            log.debug("Keep image: $parentRepoPath - expireDate is in future")
            break
        }

        def manifest
        try (InputStream stream = repositories.getContent(manifestInfo.repoPath).inputStream) {
            manifest = new JsonSlurper().parse(stream)
        }
        long lastPullTime = 0
        for (layer in manifest.layers) {
            String digest = layer.digest
            RepoPath layerPath = layerPaths.get(digest.replaceAll(":", "__"))
            if (layerPath) {
                StatsInfo statsInfo = repositories.getStats(layerPath)
                if ((statsInfo != null) && (lastPullTime < statsInfo.lastDownloaded)) {
                    lastPullTime = statsInfo.lastDownloaded
                }
            }
        }

        if ((info.pullProtectDays != null) && (lastPullTime > 0)) {
            if (info.pullProtectDays < 0) {
                log.debug("Keep image: $parentRepoPath - pulled and protected by infinity time")
                break
            }
            if (lastPullTime + info.pullProtectDays * oneDay > now) {
                log.debug("Keep image: $parentRepoPath - pulled recently")
                break
            }
        }

        if ((info.maxCount == null) && (info.maxDays == null)) {
            info.maxDays = defaultMaxDays
        }

        isDefault = true
        if (info.maxDays != null) {
            if (info.maxDays < 0) {
                log.debug("Keep image: $parentRepoPath - keep forever")
                break
            }
            if (info.createdTime + info.maxDays * oneDay <= now) {
                log.warn("Removing image: $parentRepoPath - created too long")
                removeSet << parentRepoPath
                break
            }
            isDefault = false
        }

        if (info.maxCount != null) {
            if (!parentGroups.containsKey(info.maxCountGroup)) {
                parentGroups.put(info.maxCountGroup, new ArrayList<>())
            }
            parentGroups[info.maxCountGroup] << info
            break
        }
        log.debug("Keep image: $parentRepoPath - created recently")
        break
    }

    // Process registry and subregistry
    Map<String, List<ImageInfo>> childGroups = new HashMap<>()
    for (item in pendingQueue) {
        registryTraverse(item, defaultMaxDays, dryRun, childGroups, deleted)
    }

    for (group in childGroups.values()) {
        group = group.sort { -it.createdTime }

        int keeped = 0
        for (item in group) {
            if (keeped < item.maxCount) {
                log.debug("Keep image: ${item.repo.repoPath} - max count not reached")
                keeped++
                continue
            }
            log.warn("Removing image: ${item.repo.repoPath} - too many images")
            removeSet << item.repo.repoPath
        }
    }

    // Remove images from repository
    for (RepoPath item in removeSet) {
        deleted << item.id
        if (!dryRun) repositories.delete(item)
    }
    return deleted
}

ImageInfo getImageInfo(ItemInfo repo, ItemInfo item) {
    try {
        String expireDateProp = repositories.getProperty(item.repoPath, "com.joom.retention.expireDate")
        if (expireDateProp == null) {
            expireDateProp = repositories.getProperty(repo.repoPath, "com.joom.retention.expireDate")
        }
        String pullProtectDaysProp = repositories.getProperty(item.repoPath, "docker.label.com.joom.retention.pullProtectDays")
        String maxDaysProp = repositories.getProperty(item.repoPath, "docker.label.com.joom.retention.maxDays")
        String maxCountProp = repositories.getProperty(item.repoPath, "docker.label.com.joom.retention.maxCount")
        String maxCountGroupProp = repositories.getProperty(item.repoPath, "docker.label.com.joom.retention.maxCountGroup")
        return new ImageInfo(
                repo: repo,

                // Properties
                expireDate: expireDateProp ? DateTime.parse(expireDateProp).millis : 0L,

                // Labels
                pullProtectDays: pullProtectDaysProp ? pullProtectDaysProp.toInteger() : null,
                maxDays: maxDaysProp ? maxDaysProp.toInteger() : null,
                maxCount: maxCountProp ? maxCountProp.toInteger() : null,
                maxCountGroup: maxCountGroupProp ? maxCountGroupProp : "",

                // Image info
                createdTime: item.lastModified,
        )
    } catch (IllegalArgumentException e) {
        log.error(e.printStackTrace())
        return null
    }
}
