Artifactory Clean Docker Images User Plugin
===========================================

This plugin is used to clean Docker repositories based on configured cleanup
policies.

Configuration
-------------

The `cleanDockerImages.properties` file has the following field:

- `dockerRepos`: A list of Docker repositories to clean. If a repo is not in
  this list, it will not be cleaned.

- `defaultMaxDays`: A default value for `maxDays` for images without `maxCount`.

For example:

``` json
dockerRepos = ["example-docker-local", "example-docker-local-2"]
defaultMaxDays = 3
```

Usage
-----

Cleanup policies are specified as labels on the Docker image. Currently, this
plugin supports the following policies:

- `expireDate` property: This image should be removed after this date.
  If `expireDate` property is exists, all other policies is ignored.
- `pullProtectDays`: Image would be protected for deleting after last
  pull.
- `maxDays`: The maximum number of days a Docker image can exist in an
  Artifactory repository. Any images older than this will be deleted.
- `maxCount`: The maximum number of versions of a particular image which should
  exist. For example, if there are 10 versions of a Docker image and `maxCount`
  is set to 6, the oldest 4 versions of the image will be deleted.


To set these labels for an image, add them to the Dockerfile before building:

``` dockerfile
LABEL com.joom.retention.maxCount="10"
LABEL com.joom.retention.maxDays="7"
```

When a Docker image is deployed, Artifactory will automatically create
properties reflecting each of its labels. These properties are read by the
plugin in order to decide on the cleanup policy for the image.

Cleanup can be triggered via a REST endpoint. For example:

``` shell
curl -XPOST -uadmin:password http://localhost:8081/artifactory/api/plugins/execute/cleanDockerImages
```

A dry run can also be triggered:

``` shell
curl -XPOST -uadmin:password "http://localhost:8081/artifactory/api/plugins/execute/cleanDockerImages?params=dryRun=true"
```
