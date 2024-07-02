
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
    echo "Check out component: $comp \n"
    def giturl = comp['giturl']
    def version = comp['version']
    if (version == null && giturl == null) {
        error "giturl must be specified for: $comp when version is not specified"
    }
    def compName = comp['name']
    if (compName == null && giturl != null) {
        compName = nameOfGit(giturl)
    }
    def versionName = comp.get("version.name", COMP_VERSIONS.get(compName))
    if (versionName == null) {
        def message = error "FAIL:: No version name found for component: ${compName}"
        error "$message"
    }
    def versionFile = core ? "coreversions" : "versions"
    sh: "mkdir -p $workdir/$compName"
    def buildScripts = ""
    if (version == null) {
        echo "Check out $compName from $giturl ..."
        dir("$workdir/$compName") {
            git branch: comp['branch'], url: giturl
        }
        def branch = comp['branch']
        def buildCmd = comp.get("build-command", "mvn clean install")
        def jdk = comp['jdk']
        def javaHomeSwitch = ""
        if (jdk != null) {
            if (jdk >= 17) {
                javaHomeSwitch = "JAVA_HOME=\$JAVA17_HOME && "
            } else if (jdk >= 11) {
                javaHomeSwitch = "JAVA_HOME=\$JAVA11_HOME && "
            }
        }
        def srcPathSwitch = ""
        def path = comp['path']
        if (path != null) {
            srcPathSwitch = "/" + path
        }
        echo "source path: $srcPathSwitch"
        def buildOpts = comp.get("build-options", "-DskipTests")
        def wf_core_options = ""
        if (compName == "wildfly-core" || compName == "wildfly-core-eap" || compName == "wildfly-core-private") {
            wf_core_options = """
if [ -f "$workspace/coreversions" ]; then
    coreversions="\$(cat $workspace/coreversions)"
fi
"""
        }
        buildScripts = """#!/bin/bash
set -ex
echo "Build $compName, Branch to build and use is: $branch, the source path is: $srcPathSwitch"
pushd $workdir/$compName$srcPathSwitch
coreversions=""
$wf_core_options
$javaHomeSwitch $buildCmd $buildOpts \$coreversions \${MAVEN_SETTINGS_XML_OPTION}

# get the version, and append it to versions file in workspace
$javaHomeSwitch mvn \${MAVEN_SETTINGS_XML_OPTION} help:evaluate -Dexpression=project.version
version="\$(mvn \${MAVEN_SETTINGS_XML_OPTION} help:evaluate -Dexpression=project.version | grep -e '^[^\\[]')"
echo -n " -D${versionName}=\$version" >> $workspace/$versionFile
popd
        """
    } else {
        buildScripts = """#!/bin/bash
set -ex
echo -n " -D${versionName}=$version" >> $workspace/$versionFile
        """
    }

    // return the full build scripts
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
    sh: 'mkdir -p $workdir/eap'
    def eapGitUrl = "$env.GIT_REPOSITORY_URL"
    def eapBranch = "$env.GIT_REPOSITORY_BRANCH"
    def buildOptions = "$env.BUILD_OPTS"
    def testOptions = "$env.TESTSUITE_OPTS"
    if (payload.containsKey("eap")) {
        def eap = payload['eap']
        eapGitUrl = eap.get('giturl', eapGitUrl)
        eapBranch = eap.get('branch', eapBranch)
        buildCmd = eap.get("build-command", "mvn clean install")
        buildOptions = eap.get('build-options', buildOptions)
        testOptions = eap.get('test-options', testOptions)
    }
    if (buildOptions == null) {
        buildOptions = ""
    }
    if (testOptions == null) {
        testOptions = ""
    }
    dir("$workdir/eap") {
        git branch: eapBranch, url: eapGitUrl
    }
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
mvn clean install $buildOptions \$versions \$coreversions \${MAVEN_SETTINGS_XML_OPTION} -B
popd
        """
    writeFile file: "$workdir/eap/build-eap.sh", text: buildCommands

    def testCommands = """#!/bin/bash
set -ex
echo "Test EAP"
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
mvn clean install $testOptions \$versions \$coreversions \$TESTSUITE_OPTS
popd
        """
    writeFile file: "$workdir/eap/test-eap.sh", text: testCommands
}

// remember to return this to be able to run in pipeline job
return this