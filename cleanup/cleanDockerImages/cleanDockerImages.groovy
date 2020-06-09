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
import org.artifactory.md.Properties
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.repo.Repositories
import org.joda.time.DateTime

import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

Repositories repositories

class GroupInfo {
    RepoPath keep
    List<RepoPath> images
}

class ImageGroups {
    Map<String, GroupInfo> groups = new HashMap<>()
    List<String> deleted = new ArrayList<>()
    Repositories repositories
    def log
    boolean dryRun

    RepoPath getProtectedBy(String deployGroup) {
        if (deployGroup == null) {
            return null
        }
        GroupInfo info = groups.get(deployGroup)
        if (info == null) {
            return null
        }
        return info.keep
    }

    void protectGroup(String deployGroup, RepoPath path) {
        GroupInfo info = groups.get(deployGroup)
        if (info == null) {
            info = new GroupInfo()
            groups.put(deployGroup, info)
        }
        if (info.keep == null) {
            info.keep = path
            info.images = null
        }
    }

    void obsoleteImage(ImageInfo image) {
        String deployGroup = image.getDeployGroup()
        if (deployGroup == null) {
            deleteImage(image.repo.repoPath)
            return
        }
        GroupInfo info = groups.get(deployGroup)
        if (info == null) {
            info = new GroupInfo()
            groups.put(deployGroup, info)
        }
        if (info.keep != null) {
            log.debug("Keep image: ${image.repo.repoPath} - image in same group (${info.keep}) is protected")
            return
        }
        if (info.images == null) {
            info.images = new ArrayList<>()
        }
        info.images.add(image.repo.repoPath)
    }

    void cleanup() {
        for (info in groups.values()) {
            if (info.images == null) {
                continue
            }
            for (image in info.images) {
                deleteImage(image)
            }
        }
    }

    void deleteImage(RepoPath path) {
        log.warn("Removing image: ${path}")
        deleted << path.id
        if (!dryRun) {
            repositories.delete(path)
        }
    }
}

class ImageInfo {
    Repositories repositories
    ItemInfo manifest
    ItemInfo repo
    Map<String, RepoPath> layerPaths

    // Lazy
    Properties _manifestProps
    Properties _repoProps

    long getCreatedTime() {
        return manifest.lastModified
    }

    Properties getManifestProps() {
        if (_manifestProps == null) {
            _manifestProps = repositories.getProperties(manifest.repoPath)
        }
        return _manifestProps
    }

    Properties getRepoProps() {
        if (_repoProps == null) {
            _repoProps = repositories.getProperties(repo.repoPath)
        }
        return _repoProps
    }

    long getExpireDate() {
        String expireDateProp = getManifestProps().getFirst("com.joom.retention.expireDate")
        if (expireDateProp == null) {
            expireDateProp = getRepoProps().getFirst("com.joom.retention.expireDate")
        }
        return expireDateProp ? DateTime.parse(expireDateProp).millis : 0L
    }

    Integer getPullProtectDays() {
        String pullProtectDaysProp = getManifestProps().getFirst("docker.label.com.joom.retention.pullProtectDays")
        return pullProtectDaysProp ? pullProtectDaysProp.toInteger() : null
    }

    Integer getMaxCount() {
        String maxCountProp = getManifestProps().getFirst("docker.label.com.joom.retention.maxCount")
        return maxCountProp ? maxCountProp.toInteger() : null
    }

    String getMaxCountGroup() {
        String maxCountGroupProp = getManifestProps().getFirst("docker.label.com.joom.retention.maxCountGroup")
        return maxCountGroupProp ? maxCountGroupProp : ""
    }

    Integer getMaxDays() {
        String maxDaysProp = getManifestProps().getFirst("docker.label.com.joom.retention.maxDays")
        return maxDaysProp ? maxDaysProp.toInteger() : null
    }

    String getDeployGroup() {
        def deployGroupProp = getManifestProps().getFirst("docker.label.com.joom.retention.deployGroup")
        return deployGroupProp ? deployGroupProp : null
    }

    boolean isPullProtected() {
        def pullProtectDays = getPullProtectDays()
        if (pullProtectDays == null) {
            return false
        }

        // Load docker manifest
        def parsedManifest
        InputStream stream = repositories.getContent(manifest.repoPath).inputStream
        try {
            parsedManifest = new JsonSlurper().parse(stream)
        } finally {
            stream.close()
        }

        long now = new Date().time
        long pullDeadline = now - pullProtectDays * TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)
        for (layer in parsedManifest.layers) {
            String digest = layer.digest
            RepoPath layerPath = layerPaths.get(digest.replaceAll(":", "__"))
            if (layerPath) {
                StatsInfo statsInfo = repositories.getStats(layerPath)
                if ((statsInfo == null) || (statsInfo.lastDownloaded <= 0)) {
                    continue
                }
                if ((statsInfo.lastDownloaded > pullDeadline) || (getPullProtectDays() < 0)) {
                    return true
                }
            }
        }
        return false
    }
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
        def dryRun = params['dryRun'] ? Boolean.parseBoolean(params['dryRun'][0]) : false

        for (repository in getMatchedRepositories(repos)) {
            log.debug("Cleaning Docker images in repo: $repository")
            def del = buildParentRepoPaths(RepoPathFactory.create(repository), defaultMaxDays, dryRun)
            deleted.addAll(del)
        }
        def json = [status: 'okay', dryRun: dryRun, deleted: deleted]
        message = new JsonBuilder(json).toPrettyString()
        status = 200
    }
}

List<String> getMatchedRepositories(Collection<String> patterns) {
    List<String> found = new ArrayList<>()
    repositories.getLocalRepositories().each {
        if (isMatched(it, patterns) && repositories.getRepositoryConfiguration(it).isEnableDockerSupport()) {
            found.add(it)
        }
    }
    return found
}

static boolean isMatched(String name, Collection<String> patterns) {
    for (pattern in patterns) {
        if (Pattern.matches(pattern, name)) {
            return true
        }
    }
    return false
}

def buildParentRepoPaths(path, int defaultMaxDays, boolean dryRun) {
    ImageGroups groups = new ImageGroups(
            repositories: repositories,
            log: log,
            dryRun: dryRun,
    )
    registryTraverse(repositories.getItemInfo(path), groups, defaultMaxDays)
    groups.cleanup()
    return groups.deleted
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
def registryTraverse(ItemInfo parentInfo, ImageGroups groups, int defaultMaxDays, Map<String, List<ImageInfo>> parentGroups = null, List<String> deleted = []) {
    List<ImageInfo> removeSet = new ArrayList<>()
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

    // Process registry and subregistry
    Map<String, List<ImageInfo>> childGroups = new HashMap<>()
    for (item in pendingQueue) {
        registryTraverse(item, groups, defaultMaxDays, childGroups, deleted)
    }

    try {
        while (manifestInfo != null) {
            log.debug("Scanning File: $manifestInfo.repoPath")
            ImageInfo info = getImageInfo(parentInfo, manifestInfo)
            if (info == null) {
                log.error("Invalid image labels: $parentRepoPath")
                break
            }
            info.layerPaths = layerPaths

            String deployGroup = info.getDeployGroup()
            long expireDate = info.getExpireDate()
            if (expireDate > 0) {
                if (expireDate >= now) {
                    log.warn("Removing image: $parentRepoPath - by expireDate")
                    groups.deleteImage(info.repo.repoPath)
                    break
                }
                log.debug("Keep image: $parentRepoPath - expireDate is in future")
                groups.protectGroup(deployGroup, info.repo.repoPath)
                break
            }

            def maxCount = info.getMaxCount()
            def maxDays = info.getMaxDays()
            if ((maxCount == null) && (maxDays == null)) {
                maxDays = defaultMaxDays
            }

            boolean maxDaysExpired = (maxDays != null) && (info.getCreatedTime() + maxDays * oneDay <= now)
            if ((!maxDaysExpired) && (maxCount == null)) {
                log.debug("Keep image: $parentRepoPath - created recently without count limit")
                groups.protectGroup(deployGroup, info.repo.repoPath)
                break
            }

            if (maxDaysExpired) {
                log.debug("Obsolete image: $parentRepoPath - created too long")
                removeSet << info
                break
            }

            if (maxCount != null) {
                String maxCountGroup = info.getMaxCountGroup()
                if (!parentGroups.containsKey(maxCountGroup)) {
                    parentGroups.put(maxCountGroup, new ArrayList<>())
                }
                parentGroups[maxCountGroup] << info
                break
            }
            log.debug("Keep image: $parentRepoPath - created recently")
            groups.protectGroup(deployGroup, item)
            break
        }

        for (List<ImageInfo> imageGroup in childGroups.values()) {
            imageGroup = imageGroup.sort { -it.getCreatedTime() }

            int keeped = 0
            for (ImageInfo item in imageGroup) {
                if (keeped < item.getMaxCount()) {
                    log.debug("Keep image: ${item.repo.repoPath} - max count not reached")
                    groups.protectGroup(item.getDeployGroup(), item.repo.repoPath)
                    keeped++
                    continue
                }
                log.debug("Obsolete image: ${item.repo.repoPath} - too many images")
                removeSet << item
            }
        }

        // Remove images from repository
        for (ImageInfo item in removeSet) {
            def deployGroup = item.getDeployGroup()
            def protectedBy = groups.getProtectedBy(deployGroup)
            if (protectedBy) {
                log.debug("Keep image: $parentRepoPath - obsolete, but protected by $protectedBy")
                continue
            }
            if (item.isPullProtected()) {
                log.debug("Keep image: $parentRepoPath - obsolete, but pulled recently")
                groups.protectGroup(deployGroup, item.repo.repoPath)
                continue
            }
            groups.obsoleteImage(item)
        }
    } catch (IllegalArgumentException e) {
        log.error(e.printStackTrace())
    }
}

ImageInfo getImageInfo(ItemInfo repo, ItemInfo item) {
    return new ImageInfo(
            repositories: repositories,
            manifest: item,
            repo: repo,
    )
}
