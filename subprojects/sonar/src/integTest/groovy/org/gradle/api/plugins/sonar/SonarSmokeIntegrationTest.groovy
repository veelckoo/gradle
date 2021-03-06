/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.plugins.sonar

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.mortbay.jetty.Server
import org.mortbay.jetty.webapp.WebAppContext
import org.gradle.integtests.fixtures.TestResources

import org.junit.Rule
import spock.lang.Shared
import spock.lang.AutoCleanup
import org.gradle.util.Resources
import org.gradle.util.TemporaryFolder
import org.gradle.util.ClasspathUtil
import org.gradle.util.AvailablePortFinder

class SonarSmokeIntegrationTest extends AbstractIntegrationSpec {
    @Shared
    AvailablePortFinder portFinder = AvailablePortFinder.createPrivate()

    @AutoCleanup("stop")
    Server webServer = new Server(0)

    @Rule
    TemporaryFolder tempDir = new TemporaryFolder()

    @Rule
    TestResources testResources

    int databasePort

    def setup() {
        def classpath = ClasspathUtil.getClasspath(getClass().classLoader).collect() { new File(it.toURI()) }
        def warFile = classpath.find { it.name == "sonar-test-server-3.2.war" }
        assert warFile
        def zipFile = classpath.find { it.name == "sonar-test-server-home-dir-3.2.zip" }
        assert zipFile

        def sonarHome = tempDir.createDir("sonar-home")
        System.setProperty("SONAR_HOME", sonarHome.path)
        new AntBuilder().unzip(src: zipFile, dest: sonarHome, overwrite: true)

        databasePort = portFinder.nextAvailable
        sonarHome.file("conf/sonar.properties") << """
sonar.jdbc.username=sonar
sonar.jdbc.password=sonar
sonar.jdbc.url=jdbc:h2:mem:sonartest
sonar.embeddedDatabase.port=$databasePort
        """.trim()

        def context = new WebAppContext()
        context.war = warFile
        webServer.addHandler(context)
        webServer.start()
    }

    def "can run Sonar analysis"() {
        distribution.requireIsolatedDaemons()
        // Without forking, we run into problems with Sonar's BootStrapClassLoader, at least when running from IDEA.
        // Problem is that BootStrapClassLoader, although generally isolated from its parent(s), always
        // delegates to the system class loader. That class loader holds the test class path and therefore
        // also the Sonar dependencies with "provided" scope. Hence, the Sonar dependencies get loaded by
        // the wrong class loader.
        when:
        executer.withForkingExecuter()
                .withArgument("-PserverUrl=http://localhost:${webServer.connectors[0].localPort}")
                .withArgument("-PdatabaseUrl=jdbc:h2:tcp://localhost:$databasePort/mem:sonartest")
                .withTasks("sonarAnalyze").run()

        then:
        noExceptionThrown()

    }
}
