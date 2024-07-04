
// This is a function can be run in Jenkins job dsl plugin, using `def allInOne = load "harmonia/AllInOne.groovy"`

def nameOfGit(def giturl) {
    def lastSlash = giturl.lastIndexOf('/')
    return giturl.substring(lastSlash + 1)
}

def checkOutComp(def workdir, def comp, def core) {
    echo "Check out component: $comp \n"
    def giturl = comp['giturl']
    def version = comp['version']
    // if version is specified, it will skip build, and use the version directly.
    // if not, and branch is specified, it will be built from that branch.
    if (version == null && giturl == null) {
        error "giturl must be specified for: $comp when version is not specified"
    }
    def compName = comp['name']
    if (compName == null && giturl != null) {
        compName = nameOfGit(giturl)
    }
    if (compName == null) {
        error "Unknown name for $comp"
    }
    def versionName = comp.get("version.name")
    if (versionName == null) {
        error "FAIL:: No version name found for component: ${compName}"
    }
    def versionFile = comp.get("core", false) ? "versions/coreversions" : "versions/versions"
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
            echo "Switch JDK to $javaHomeSwitch"
        }
        def srcPathSwitch = ""
        def path = comp['path']
        if (path != null) {
            srcPathSwitch = "/" + path
            echo "source path: $srcPathSwitch"
        }
        def buildOpts = comp.get("build-options", "-DskipTests")
        echo "Build options for $compName : $buildOpts"
        def wf_core_options = ""
        if (core) {
            wf_core_options = """
if [ -f "$workspace/versions/coreversions" ]; then
    coreversions="\$(cat $workspace/versions/coreversions)"
fi
"""
        }
        buildScripts = """#!/bin/bash
set -ex
echo "Build $compName, Branch to build and use is: $branch, the source path is: $srcPathSwitch"
pushd $workdir/$compName$srcPathSwitch
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
            if (comp.get("core", false)) {
                env.HAS_CORE_COMPONENTS = 'true'
                echo "INFO: Checking out the core component $compName ..."
                def buildScripts = checkOutComp(workdir, comp, false)
                def buildScriptFile = "$workspace/scripts/build-${compName}.sh"
                writeFile file: "$buildScriptFile", text: buildScripts
                core_scripts_file += "$buildScriptFile \n"
            } else {
                env.HAS_COMPONENTS = 'true'
                echo "INFO: Checking out EAP component $compName ..."
                def buildScripts = checkOutComp(workdir, comp, false)
                def buildScriptFile = "$workspace/scripts/build-${compName}.sh"
                writeFile file: "$buildScriptFile", text: buildScripts
                scripts_file += "$buildScriptFile \n"
            }
        }
        writeFile file: "$workspace/scripts/core_components", text: core_scripts_file
        writeFile file: "$workspace/scripts/components", text: scripts_file
    }

    // wildfly-core
    def wc = "wildfly-core"
    if (payload.containsKey(wc)) {
        env.HAS_CORE = 'true'
        // generate scripts to build wildfly-core
        payload[wc].put("version.name", "version.org.wildfly.core")
        if (!payload[wc].containsKey("build-options")) {
            payload[wc].put("build-options", "-DallTests") // default use -DallTests for wildfly-core unless it is specified
        }
        def buildCommands = checkOutComp(workdir, payload[wc], true)
        writeFile file: "$workspace/scripts/build-wildfly-core.sh", text: buildCommands
    }

    if (env.HAS_CORE_COMPONENTS && !env.HAS_CORE) {
        error "When core components are specified, the wildfly-core needs to be specified as well."
    }

    // eap
    sh: 'mkdir -p $workdir/eap'
    def eapGitUrl = "$env.GIT_REPOSITORY_URL"
    def eapBranch = "$env.GIT_REPOSITORY_BRANCH"
    def buildOptions = "-Drelease"
    def testOptions = "-DallTests"
    if (payload.containsKey("eap")) {
        def eap = payload['eap']
        eapGitUrl = eap.get('giturl', eapGitUrl)
        eapBranch = eap.get('branch', eapBranch)
        buildCmd = eap.get("build-command", "mvn clean install")
        buildOptions = eap.get('build-options', buildOptions)
        testOptions = eap.get('test-options', testOptions)
    }
    if (eapGitUrl == null || eapGitUrl == '') {
        error "giturl must be specified for: eap to build and test"
    }
    if (eapBranch == null || eapBranch == '') {
        error "branch must be specified for: eap to build and test"
    }
    dir("$workdir/eap") {
        git branch: eapBranch, url: eapGitUrl
    }
    def buildCommands = """#!/bin/bash
set -ex
echo "Build eap"
pushd $workdir/eap
coreversions=""
if [ -f "$workspace/versions/coreversions" ]; then
    coreversions="\$(cat $workspace/versions/coreversions)"
fi
versions=""
if [ -f "$workspace/versions/versions" ]; then
    versions="\$(cat $workspace/versions/versions)"
fi
echo -e "Versions are: \$versions, Core versions are: \$coreversions"
mvn clean install $buildOptions \$versions \$coreversions \${MAVEN_SETTINGS_XML_OPTION} -B
popd
        """
    writeFile file: "$workspace/scripts/build-eap.sh", text: buildCommands

    def testCommands = """#!/bin/bash
set -ex
echo "Test EAP"
pushd $workdir/eap/testsuite
coreversions=""
if [ -f "$workspace/versions/coreversions" ]; then
    coreversions="\$(cat $workspace/versions/coreversions)"
fi
versions=""
if [ -f "$workspace/versions/versions" ]; then
    versions="\$(cat $workspace/versions/versions)"
fi
echo -e "Versions are: \$versions"
mvn clean install $testOptions \$versions \$coreversions \$TESTSUITE_OPTS
popd
        """
    writeFile file: "$workspace/scripts/test-eap.sh", text: testCommands
}

// remember to return this to be able to run in pipeline job
return this