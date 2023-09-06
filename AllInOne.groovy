
def prepareScriptsFunc () {
    echo "Preparing scripts reading from payload.json in workspace: ${env.WORKSPACE}"
    def payload = readJSON file: "${env.WORKSPACE}/payload.json", returnPojo: true
    def workdir = "$workspace/workdir"

    // wildfly-core
    def wc = "wildfly-core"
    if (payload.containsKey(wc)) {
        // generate scripts to build wildfly-core
        def wildflycore = payload[wc]
        sh: "mkdir -p $workdir/wildfly-core"
        dir("$workdir/$wc") {
            git branch: wildflycore['branch'], url: wildflycore['giturl']
        }
        // m.get('language', 'Java')
        def buildCmd = wildflycore.get("build-command", "mvn clean install")
        def buildOpts = wildflycore["build-options"]
        def buildCommands = """#!/bin/bash
echo "Build wildfly-core"
pushd $workdir/wildfly-core
coreversions=""
if [ -f "$workspace/coreversions" ]; then
    coreversions="\$(cat $workspace/coreversions)"
fi
$buildCmd $buildOpts \$coreversions \${MAVEN_SETTINGS_XML_OPTION}

# get the version, and append it to versions file in workspace
mvn \${MAVEN_SETTINGS_XML_OPTION} help:evaluate -Dexpression=project.version
version="\$(mvn \${MAVEN_SETTINGS_XML_OPTION} help:evaluate -Dexpression=project.version | grep -e '^[^\\[]')"
echo -n " -Dversion.wildfly.core=\$version" >> $workspace/versions
popd
        """
        writeFile file: "$workdir/wildfly-core/build.sh", text: buildCommands
    }

    // eap
    if (payload.containsKey("eap")) {
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
$testCmd $testOpts \$TESTSUITE_OPTS \$versions \$coreversions 
popd
        """
        writeFile file: "$workdir/eap/test-eap.sh", text: testCommands
    }
}


return this