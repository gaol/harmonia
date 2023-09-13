
// This is a function can be run in Jenkins job dsl plugin, using `def allInOne = load "harmonia/AllInOne.groovy"`

// not all core components are listed, just some common ones for convenient.
CORE_COMPS = ["undertow", "elytron", "jboss-modules", "jboss-marshalling", "jboss-remoting", "xnio"]

// a dictionary contains name and the version string of the components used in eap and wildfly-core
COMP_VERSIONS = [
        "wildfly-core": "version.org.wildfly.core",
        "wildfly-core-eap": "version.org.wildfly.core",
        "wildfly-core-private": "version.org.wildfly.core",
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

def nameOfGit(def giturl) {
    def lastSlash = giturl.lastIndexOf('/')
    return giturl.substring(lastSlash + 1)
}

def checkOutComp(def workdir, def comp, def core) {
    def giturl = comp['giturl']
    def compName = comp.get('name', nameOfGit(giturl))
    if (giturl == null || compName == null) {
        error "giturl or name must be specified for: $comp"
    }
    echo "Check out $compName from $giturl ..."
    sh: "mkdir -p $workdir/$compName"
    dir("$workdir/$compName") {
        git branch: comp['branch'], url: giturl
    }
    def branch = comp['branch']
    def buildCmd = comp.get("build-command", "mvn clean install")
    def buildOpts = comp.get("build-options", "-DskipTests")
    def versionName = comp.get("version.name", COMP_VERSIONS.get(compName))
    if (versionName == null) {
        def message = error "FAIL:: No version name found for component: ${compName}"
        error "$message"
    }
    def versionFile = core ? "coreversions" : "versions"
    def wf_core_options = ""
    if (compName == "wildfly-core" || compName == "wildfly-core-eap" || compName == "wildfly-core-private") {
        wf_core_options = """
if [ -f "$workspace/coreversions" ]; then
    coreversions="\$(cat $workspace/coreversions)"
fi
"""
    }
    // return the full build scripts
    def buildScripts = """#!/bin/bash
set -ex
echo "Build $compName, Branch to build and use is: $branch"
pushd $workdir/$compName
coreversions=""
$wf_core_options
$buildCmd $buildOpts \$coreversions \${MAVEN_SETTINGS_XML_OPTION}

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
    echo "\n=============\nTry to test the payload:\n$payload \n====================\n"
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
        def buildCommands = checkOutComp(workdir, payload[wc], false)
        writeFile file: "$workdir/wildfly-core/build.sh", text: buildCommands
    }

    // eap
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
mvn clean install \$versions \$coreversions \${MAVEN_SETTINGS_XML_OPTION} -B \${BUILD_OPTS}
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
mvn clean install \$versions \$coreversions \$TESTSUITE_OPTS
popd
        """
    writeFile file: "$workdir/eap/test-eap.sh", text: testCommands
}

// remember to return this to be able to run in pipeline job
return this