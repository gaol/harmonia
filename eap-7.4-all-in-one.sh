#!/bin/bash
#
#
# Build EAP 7.4.x all in one
#
set -eo pipefail

full_path="$(realpath "$0")"
dir_path="$(dirname "$full_path")"
source "${dir_path}/eap-job/base.sh"

readonly MAVEN_SETTINGS_XML=${MAVEN_SETTINGS_XML-'/home/master/settings.xml'}
readonly MAVEN_VERBOSE=${MAVEN_VERBOSE}
readonly GIT_SKIP_BISECT_ERROR_CODE=${GIT_SKIP_BISECT_ERROR_CODE:-'125'}
readonly LOCAL_REPO_DIR=${LOCAL_REPO_DIR:-${WORKSPACE}/maven-local-repository}
readonly MEMORY_SETTINGS=${MEMORY_SETTINGS:-'-Xmx2048m -Xms1024m'}
readonly SUREFIRE_MEMORY_SETTINGS=${SUREFIRE_MEMORY_SETTINGS:-'-Xmx1024m'}
readonly BUILD_OPTS=${BUILD_OPTS:-'-Drelease'}
readonly MAVEN_WAGON_HTTP_POOL=${WAGON_HTTP_POOL:-'false'}
readonly MAVEN_WAGON_HTTP_MAX_PER_ROUTE=${MAVEN_WAGON_HTTP_MAX_PER_ROUTE:-'3'}
readonly SUREFIRE_FORKED_PROCESS_TIMEOUT=${SUREFIRE_FORKED_PROCESS_TIMEOUT:-'90000'}
readonly FAIL_AT_THE_END=${FAIL_AT_THE_END:-'-fae'}
readonly RERUN_FAILING_TESTS=${RERUN_FAILING_TESTS:-'0'}
readonly OLD_RELEASES_FOLDER=${OLD_RELEASES_FOLDER:-/opt/old-as-releases}
readonly FOLDER_DOES_NOT_EXIST_ERROR_CODE='3'
readonly PARAMS=${@}

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

mkdir -p "${WORKSPACE}/scripts"
cd "${WORKSPACE}/scripts"

# for each comp

# comp_version="$(mvn help:evaluate -Dexpression=project.version | grep -e '^[^\[]')"

# each time it runs one of the following:
# build core components
# build core
# build non-core components
# build eap

# build and test eap
if [ "${BUILD_COMMAND}" = 'core' ]; then
  # build core
  echo "build core in ${WORKSPACE}/wildfly-core/"
  ls -lah "${WORKSPACE}/wildfly-core/"
  bash -x "${WORKSPACE}/wildfly-core/build.sh" 2>&1
  status=${?}
  if [ "${status}" -ne 0 ]; then
    echo "Build Core failed"
    exit "${GIT_SKIP_BISECT_ERROR_CODE}"
  fi
elif [ "${BUILD_COMMAND}" = 'eap-build' ]; then
  # build eap
  echo "build eap in ${WORKSPACE}/eap/"
  bash -x "${WORKSPACE}/eap/build-eap.sh" 2>&1
elif [ "${BUILD_COMMAND}" = 'eap-test' ]; then
  # test eap
  echo "test eap in ${WORKSPACE}/eap/"
  bash -x "${WORKSPACE}/eap/test-eap.sh" 2>&1
fi


