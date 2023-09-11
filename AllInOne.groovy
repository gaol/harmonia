
// This is a function can be run in Jenkins job dsl plugin, using `def allInOne = load "harmonia/AllInOne.groovy"`

// not all core components are listed, just some common ones for convenient.
CORE_COMPS = ["undertow", "elytron", "jboss-modules", "jboss-marshalling", "jboss-remoting", "xnio"]

// a dictionary contains name and the version string of the components used in eap and wildfly-core
COMP_VERSIONS = [
        "wildfly-core": "version.org.wildfly.core",
        "undertow": "version.io.undertow",
        "elytron": "version.org.wildfly.security.elytron",
        "xnio": "version.org.jboss.xnio",
        "jbos-remoting": "version.org.jboss.remoting",
        "jboss-marshalling": "version.org.jboss.marshalling.jboss-marshalling",
        "jboss-modules": "version.org.jboss.modules.jboss-modules",
        "hibernate": "version.org.hibernate",
        "infinispan": "version.org.infinispan",
        "ejb-client": "version.org.jboss.ejb-client",
        "hal": "version.org.jboss.hal.console",
        "ironjacamar": "version.org.jboss.ironjacamar",
        "narayana": "version.org.jboss.narayana",
        "resteasy": "version.org.jboss.resteasy"
]

def checkOutComp(def workdir, def comp, def core) {
    def compName = comp['name']
    sh: "mkdir -p $workdir/$compName"
    dir("$workdir/$compName") {
        git branch: comp['branch'], url: comp['giturl']
    }
    def branch = comp['branch']
    def buildCmd = comp.get("build-command", "mvn clean install")
    def buildOpts = comp.get("build-options", "-DskipTests")
    def versionName = comp.get("version.name", COMP_VERSIONS.get(compName))
    if (versionName == null) {
        throw new RuntimeException("FAIL:: No version name found for component: ${compName}")
    }
    def versionFile = core ? "coreversions" : "versions"
    // return the full build scripts
    def buildScripts = """#!/bin/bash
set -ex
echo "Build $compName, Branch to build and use is: $branch"
pushd $workdir/$compName
$buildCmd $buildOpts \${MAVEN_SETTINGS_XML_OPTION}

# get the version, and append it to versions file in workspace
mvn \${MAVEN_SETTINGS_XML_OPTION} help:evaluate -Dexpression=project.version
version="\$(mvn \${MAVEN_SETTINGS_XML_OPTION} help:evaluate -Dexpression=project.version | grep -e '^[^\\[]')"
echo -n " -D${versionName}=\$version" >> $workspace/$versionFile
popd
        """
    return buildScripts
}

def prepareScripts () {
    echo "Preparing scripts reading from payload.json in workspace: ${env.WORKSPACE}"
    def payload = readJSON file: "${env.WORKSPACE}/payload.json", returnPojo: true
    def workdir = "$workspace/workdir"

    // components
    def core_scripts_file = ""
    def scripts_file = ""
    if (payload.containsKey("components")) {
        def comps = payload["components"]
        for (comp in comps) {
            def compName = comp['name']
            if (comp.get("core", CORE_COMPS.contains(compName))) {
                env.HAS_CORE_COMPONENTS = 'true'
                def buildScripts = checkOutComp(workdir, comp, true)
                def buildScriptFile = "$workdir/$compName/build-${compName}.sh"
                writeFile file: "$buildScriptFile", text: buildScripts
                core_scripts_file += "$buildScriptFile \n"
            } else {
                env.HAS_COMPONENTS = 'true'
                def buildScripts = checkOutComp(workdir, comp, false)
                def buildScriptFile = "$workdir/$compName/build-${compName}.sh"
                writeFile file: "$buildScriptFile", text: buildScripts
                scripts_file += "$buildScriptFile \n"
            }
        }
        writeFile file: "$workspace/core_components", text: core_scripts_file
        writeFile file: "$workspace/components", text: scripts_file
    }

    // wildfly-core
    def wc = "wildfly-core"
    if (payload.containsKey(wc)) {
        env.HAS_CORE = 'true'
        // generate scripts to build wildfly-core
        def wildflycore = payload[wc]
        sh: "mkdir -p $workdir/wildfly-core"
        dir("$workdir/$wc") {
            git branch: wildflycore['branch'], url: wildflycore['giturl']
        }
        def branch = wildflycore['branch']
        def buildCmd = wildflycore.get("build-command", "mvn clean install")
        def buildOpts = wildflycore["build-options"]
        def wcVersion = COMP_VERSIONS.get(wc)
        def buildCommands = """#!/bin/bash
set -ex
echo "Build wildfly-core, The branch to build and use is: $branch"
pushd $workdir/wildfly-core
coreversions=""
if [ -f "$workspace/coreversions" ]; then
    coreversions="\$(cat $workspace/coreversions)"
fi
$buildCmd $buildOpts \$coreversions \${MAVEN_SETTINGS_XML_OPTION}

# get the version, and append it to versions file in workspace
mvn \${MAVEN_SETTINGS_XML_OPTION} help:evaluate -Dexpression=project.version
version="\$(mvn \${MAVEN_SETTINGS_XML_OPTION} help:evaluate -Dexpression=project.version | grep -e '^[^\\[]')"
echo -n " -D${wcVersion}=\$version" >> $workspace/versions
popd
        """
        writeFile file: "$workdir/wildfly-core/build.sh", text: buildCommands
    }

    // eap
    if (payload.containsKey("eap")) {
        env.HAS_EAP = 'true'
        // generate scripts to build eap
        def eap = payload['eap']
        sh: 'mkdir -p $workdir/eap'
        dir("$workdir/eap") {
            git branch: eap['branch'], url: eap['giturl']
        }
        def buildCmd = eap.get("build-command", "mvn clean install")
        def buildOpts = eap["build-options"]
        def testCmd = eap.get("test-command", "mvn clean install")
        def testOpts = eap["test-options"]
        def buildCommands = """#!/bin/bash
set -ex
echo "Build eap"
pushd $workdir/eap
coreversions=""
if [ -f "$workspace/coreversions" ]; then
    coreversions="\$(cat $workspace/coreversions)"
fi
versions=""
if [ -f "$workspace/versions" ]; then
    versions="\$(cat $workspace/versions)"
fi
echo -e "Versions are: \$versions, Core versions are: \$coreversions"
$buildCmd $buildOpts \$versions \$coreversions \${MAVEN_SETTINGS_XML_OPTION} -B \${BUILD_OPTS}
popd
        """
        writeFile file: "$workdir/eap/build-eap.sh", text: buildCommands

        def testCommands = """#!/bin/bash
set -ex
echo "Test eap"
pushd $workdir/eap/testsuite
coreversions=""
if [ -f "$workspace/coreversions" ]; then
    coreversions="\$(cat $workspace/coreversions)"
fi
versions=""
if [ -f "$workspace/versions" ]; then
    versions="\$(cat $workspace/versions)"
fi
echo -e "Versions are: \$versions"
$testCmd $testOpts \$versions \$coreversions \$TESTSUITE_OPTS
popd
        """
        writeFile file: "$workdir/eap/test-eap.sh", text: testCommands
    }
}

// remember to return this to be able to run in pipeline job
return this