import groovy.json.JsonSlurper
import org.apache.http.client.HttpResponseException
import org.jfrog.artifactory.client.ArtifactoryClientBuilder
import org.jfrog.artifactory.client.model.repository.settings.impl.DockerRepositorySettingsImpl
import spock.lang.Specification

class CleanDockerImagesTest extends Specification {
    def 'simple clean docker images plugin test'() {
        setup:
        def baseurl = 'http://localhost:8088/artifactory'
        def artifactory = ArtifactoryClientBuilder.create().setUrl(baseurl)
                .setUsername('admin').setPassword('password').build()

        def builder = artifactory.repositories().builders()
        def local = builder.localRepositoryBuilder().key('example-docker-local')
                .repositorySettings(new DockerRepositorySettingsImpl()).build()
        artifactory.repositories().create(0, local)
        def repo = artifactory.repository('example-docker-local')
        mkImage(repo, 'foo1/bar/manifest.json', 'text1', 1)
        mkImage(repo, 'foo1/baz/manifest.json', 'text2', 1)
        mkImage(repo, 'foo1/ban/manifest.json', 'text3', 1)
        mkImage(repo, 'foo2/bar/manifest.json', 'text4', 2)
        mkImage(repo, 'foo2/baz/manifest.json', 'text5', 2)
        mkImage(repo, 'foo2/ban/manifest.json', 'text6', 2)
        mkImage(repo, 'foo3/bar/manifest.json', 'text7', 3)
        mkImage(repo, 'foo3/baz/manifest.json', 'text8', 3)
        mkImage(repo, 'foo3/ban/manifest.json', 'text9', 3)

        when:
        def resp = artifactory.plugins().execute('cleanDockerImages').sync()

        then:
        new JsonSlurper().parseText(resp).status == 'okay'

        when:
        repo.file('foo1/bar/manifest.json').info()

        then:
        thrown(HttpResponseException)

        when:
        repo.file('foo1/baz/manifest.json').info()

        then:
        thrown(HttpResponseException)

        when:
        repo.file('foo1/ban/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo2/bar/manifest.json').info()

        then:
        thrown(HttpResponseException)

        when:
        repo.file('foo2/baz/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo2/ban/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo3/bar/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo3/baz/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo3/ban/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        cleanup:
        repo.delete()
    }

    def 'group clean docker images plugin test'() {
        setup:
        def baseurl = 'http://localhost:8088/artifactory'
        def artifactory = ArtifactoryClientBuilder.create().setUrl(baseurl)
                .setUsername('admin').setPassword('password').build()

        def builder = artifactory.repositories().builders()
        def local = builder.localRepositoryBuilder().key('example-docker-local')
                .repositorySettings(new DockerRepositorySettingsImpl()).build()
        artifactory.repositories().create(0, local)
        def repo = artifactory.repository('example-docker-local')
        mkImage(repo, 'foo1/bar/manifest.json', 'text1', 1, 'bar')
        mkImage(repo, 'foo1/baz/manifest.json', 'text2', 1, 'baz')
        mkImage(repo, 'foo1/ban/manifest.json', 'text3', 1, 'ban')
        mkImage(repo, 'foo2/bar/manifest.json', 'text4', 1, 'bar')
        mkImage(repo, 'foo2/baz/manifest.json', 'text5', 1, 'baz')
        mkImage(repo, 'foo2/ban/manifest.json', 'text6', 1, 'ban')
        mkImage(repo, 'foo3/bar/manifest.json', 'text7', 1, 'bar')
        mkImage(repo, 'foo3/ban/manifest.json', 'text8', 1, 'ban')
        mkImage(repo, 'foo3/baz/manifest.json', 'text9', 1, 'baz')

        when:
        def resp = artifactory.plugins().execute('cleanDockerImages').sync()

        then:
        new JsonSlurper().parseText(resp).status == 'okay'

        when:
        repo.file('foo1/bar/manifest.json').info()

        then:
        thrown(HttpResponseException)

        when:
        repo.file('foo1/baz/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo1/ban/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo2/bar/manifest.json').info()

        then:
        thrown(HttpResponseException)

        when:
        repo.file('foo2/baz/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo2/ban/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo3/bar/manifest.json').info()

        then:
        thrown(HttpResponseException)

        when:
        repo.file('foo3/baz/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        when:
        repo.file('foo3/ban/manifest.json').info()

        then:
        notThrown(HttpResponseException)

        cleanup:
        repo.delete()
    }

    void mkImage(repo, path, content, ct, String group = null) {
        def countProp = 'docker.label.com.joom.retention.maxCount'
        def groupProp = 'docker.label.com.joom.retention.group'
        def stream = new ByteArrayInputStream(content.getBytes('utf-8'))
        repo.upload(path, stream).doUpload()
        repo.file(path).properties().addProperty(countProp, "$ct").doSet()
        if (group != null) {
            repo.file(path).properties().addProperty(groupProp, group).doSet()
        }
        sleep(100)
    }
}
