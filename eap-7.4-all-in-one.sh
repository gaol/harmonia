#!/bin/bash
#
#
# Build EAP 7.4.x all in one
#
set -eo pipefail

full_path="$(realpath "$0")"
dir_path="$(dirname "$full_path")"
source "${dir_path}/eap-job/base.sh"

readonly LOCAL_REPO_DIR=${LOCAL_REPO_DIR:-${WORKSPACE}/maven-local-repository}
readonly MEMORY_SETTINGS=${MEMORY_SETTINGS:-'-Xmx2048m -Xms1024m'}
readonly SUREFIRE_MEMORY_SETTINGS=${SUREFIRE_MEMORY_SETTINGS:-'-Xmx1024m'}
readonly MAVEN_IGNORE_TEST_FAILURE=${MAVEN_IGNORE_TEST_FAILURE:-'false'}
export MAVEN_WAGON_HTTP_POOL=${WAGON_HTTP_POOL:-'false'}
readonly MAVEN_WAGON_HTTP_MAX_PER_ROUTE=${MAVEN_WAGON_HTTP_MAX_PER_ROUTE:-'3'}
readonly SUREFIRE_FORKED_PROCESS_TIMEOUT=${SUREFIRE_FORKED_PROCESS_TIMEOUT:-'90000'}
readonly RERUN_FAILING_TESTS=${RERUN_FAILING_TESTS:-'0'}
readonly OLD_RELEASES_FOLDER=${OLD_RELEASES_FOLDER:-/opt/old-as-releases}
readonly FOLDER_DOES_NOT_EXIST_ERROR_CODE='3'

export MAVEN_SETTINGS_XML=${MAVEN_SETTINGS_XML-'/home/master/settings.xml'}
export MAVEN_VERBOSE=${MAVEN_VERBOSE}
export FAIL_AT_THE_END=${FAIL_AT_THE_END:-'-fae'}
export BUILD_OPTS=${BUILD_OPTS:-'-Drelease'}

# ${WORKSPACE} is under the workdir/ directory specified by build-wrapper.sh
if [ -n "${WORKSPACE}" ]; then
  echo -n "inside workspace: ${WORKSPACE}"
fi
echo '.'

check_java
configure_mvn_home
configure_mvn_opts
configure_mvn_settings

readonly PAYLOAD_URL=${PAYLOAD_URL}
if [ -z "${PAYLOAD_URL}" ]; then
  echo -n "PAYLOAD_URL needs to be specified"
  exit 1
fi

# for each comp

# comp_version="$(mvn help:evaluate -Dexpression=project.version | grep -e '^[^\[]')"

# each time it runs one of the following:
## build core components
## build core
## build non-core components
## build eap

if [ "${BUILD_COMMAND}" = 'core' ]; then
  # build core
  echo "build core in ${WORKSPACE}/wildfly-core/"
  ls -lah "${WORKSPACE}/wildfly-core/"
  bash -x "${WORKSPACE}/wildfly-core/build.sh" 2>&1
  status=${?}
  if [ "${status}" -ne 0 ]; then
    echo "Build Core Failed"
    exit "${status}"
  fi
elif [ "${BUILD_COMMAND}" = 'eap-build' ]; then
  # build eap
  echo "build eap in ${WORKSPACE}/eap/"
  # mvn clean install ${MAVEN_VERBOSE}  "${FAIL_AT_THE_END}" ${MAVEN_SETTINGS_XML_OPTION} -B ${BUILD_OPTS}
  bash -x "${WORKSPACE}/eap/build-eap.sh" 2>&1
  status=${?}
  if [ "${status}" -ne 0 ]; then
    echo "Build EAP Failed"
    exit "${status}"
  fi
elif [ "${BUILD_COMMAND}" = 'eap-test' ]; then
  # test eap
  echo "Test eap in ${WORKSPACE}/eap/"
  export TESTSUITE_OPTS="${TESTSUITE_OPTS} -Dsurefire.forked.process.timeout=${SUREFIRE_FORKED_PROCESS_TIMEOUT}"
  export TESTSUITE_OPTS="${TESTSUITE_OPTS} -Dskip-download-sources -B"
  #export TESTSUITE_OPTS="${TESTSUITE_OPTS} -Djboss.test.mixed.domain.dir=${OLD_RELEASES_FOLDER}"
  export TESTSUITE_OPTS="${TESTSUITE_OPTS} -Dmaven.test.failure.ignore=${MAVEN_IGNORE_TEST_FAILURE}"
  export TESTSUITE_OPTS="${TESTSUITE_OPTS} -Dsurefire.rerunFailingTestsCount=${RERUN_FAILING_TESTS}"
  export TESTSUITE_OPTS="${TESTSUITE_OPTS} -Dsurefire.memory.args=${SUREFIRE_MEMORY_SETTINGS}"
  export TESTSUITE_OPTS="${TESTSUITE_OPTS} ${MAVEN_SETTINGS_XML_OPTION}"
  cd "${WORKSPACE}/eap/testsuite" || exit "${FOLDER_DOES_NOT_EXIST_ERROR_CODE}"
  mvn clean
  cd ..
  # mvn clean install ${MAVEN_VERBOSE} "${FAIL_AT_THE_END}" ${TESTSUITE_OPTS}
  bash -x "${WORKSPACE}/eap/test-eap.sh" 2>&1
  status=${?}
  if [ "${status}" -ne 0 ]; then
    echo "Test EAP Failed"
    exit "${status}"
  fi
fi

