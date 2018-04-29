#!/usr/bin/env groovy

def doDebugBuild(coverageEnabled=false) {
  def dPullOrBuild = load ".jenkinsci/docker-pull-or-build.groovy"
  def parallelism = params.PARALLELISM
  def platform = sh(script: 'uname -m', returnStdout: true).trim()
  // params are always null unless job is started
  // this is the case for the FIRST build only.
  // So just set this to same value as default. 
  // This is a known bug. See https://issues.jenkins-ci.org/browse/JENKINS-41929
  if (parallelism == null) {
    parallelism = 4
  }
  if ("arm7" in env.NODE_NAME) {
    parallelism = 1
  }
  if ( env.NODE_NAME ==~ /^x86_64.+/ ) {
    parallelism = 8
  }
  // count docker image file in case there could be pre-build image saved in file
  if ( env.NODE_NAME ==~ /^x86_64.+/ ) {
    dockerImageFile = sh(script: "echo ${GIT_LOCAL_BRANCH} | md5sum | cut -c 1-8", returnStdout: true).trim()
  }
  def iC = dPullOrBuild.dockerPullOrUpdate("${platform}-develop-build",
                                           "${env.GIT_RAW_BASE_URL}/${env.GIT_COMMIT}/docker/develop/Dockerfile",
                                           "${env.GIT_RAW_BASE_URL}/${env.GIT_PREVIOUS_COMMIT}/docker/develop/Dockerfile",
                                           "${env.GIT_RAW_BASE_URL}/develop/docker/develop/Dockerfile",
                                           ['PARALLELISM': parallelism])  
  // get the image name and save it to the AWS EFS 
  if ( env.NODE_NAME ==~ /^x86_64.+/ ) {
    dockerAgentDockerImage = iC.imageName()
    sh "docker save -o ${env.JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile} ${dockerAgentDockerImage}"
  }
  iC.inside(""
  + " -e IROHA_POSTGRES_HOST=${env.IROHA_POSTGRES_HOST}"
  + " -e IROHA_POSTGRES_PORT=${env.IROHA_POSTGRES_PORT}"
  + " -e IROHA_POSTGRES_USER=${env.IROHA_POSTGRES_USER}"
  + " -e IROHA_POSTGRES_PASSWORD=${env.IROHA_POSTGRES_PASSWORD}"
  + " -v ${CCACHE_DIR}:${CCACHE_DIR}") {
    def scmVars = checkout scm
    def cmakeOptions = ""
    if ( coverageEnabled ) {
      cmakeOptions = " -DCOVERAGE=ON "
    }
    env.IROHA_VERSION = "0x${scmVars.GIT_COMMIT}"
    env.IROHA_HOME = "/opt/iroha"
    env.IROHA_BUILD = "${env.IROHA_HOME}/build"

    sh """
      ccache --version
      ccache --show-stats
      ccache --zero-stats
      ccache --max-size=5G
    """  
    sh """
      cmake \
        -DTESTING=ON \
        -H. \
        -Bbuild \
        -DCMAKE_BUILD_TYPE=Debug \
        -DIROHA_VERSION=${env.IROHA_VERSION} \
        ${cmakeOptions}
    """
    sh "cmake --build build -- -j${parallelism}"
    sh "ccache --show-stats"
  }
}

def doPreCoverageStep() {
  if ( env.NODE_NAME ==~ /^x86_64.+/ ) {
    sh "docker load -i ${JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile}"
  }
  def iC = docker.image("${dockerAgentDockerImage}")
  iC.inside(""
  + " -v ${CCACHE_DIR}:${CCACHE_DIR}") {
    sh "cmake --build build --target coverage.init.info"
  }
} 

def doTestStep() {
  if ( env.NODE_NAME ==~ /^x86_64.+/ ) {
    sh "docker load -i ${JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile}"
  }
  def iC = docker.image("${dockerAgentDockerImage}")
  sh "docker network create ${env.IROHA_NETWORK}"
  
  docker.image('postgres:9.5').withRun(""
    + " -e POSTGRES_USER=${env.IROHA_POSTGRES_USER}"
    + " -e POSTGRES_PASSWORD=${env.IROHA_POSTGRES_PASSWORD}"
    + " --name ${env.IROHA_POSTGRES_HOST}"
    + " --network=${env.IROHA_NETWORK}") {
      iC.inside(""
      + " -e IROHA_POSTGRES_HOST=${env.IROHA_POSTGRES_HOST}"
      + " -e IROHA_POSTGRES_PORT=${env.IROHA_POSTGRES_PORT}"
      + " -e IROHA_POSTGRES_USER=${env.IROHA_POSTGRES_USER}"
      + " -e IROHA_POSTGRES_PASSWORD=${env.IROHA_POSTGRES_PASSWORD}"
      + " --network=${env.IROHA_NETWORK}"
      + " -v ${CCACHE_DIR}:${CCACHE_DIR}"
      + " -v /tmp/${GIT_COMMIT}-${BUILD_NUMBER}:/tmp/${GIT_COMMIT}") {
        def testExitCode = sh(script: 'CTEST_OUTPUT_ON_FAILURE=1 cmake --build build --target test', returnStatus: true)
        if (testExitCode != 0) {
          currentBuild.result = "UNSTABLE"
        }
      }
  }
}

def doPostCoverageCoberturaStep() {
  if ( env.NODE_NAME ==~ /^x86_64.+/ ) {
    sh "docker load -i ${JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile}"
  }
  def iC = docker.image("${dockerAgentDockerImage}")
  
  iC.inside(""
  + " -v ${CCACHE_DIR}:${CCACHE_DIR}") {
    sh "cmake --build build --target coverage.info"
    sh "python /tmp/lcov_cobertura.py build/reports/coverage.info -o build/reports/coverage.xml"
    cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: '**/build/reports/coverage.xml', conditionalCoverageTargets: '75, 50, 0', failUnhealthy: false, failUnstable: false, lineCoverageTargets: '75, 50, 0', maxNumberOfBuilds: 50, methodCoverageTargets: '75, 50, 0', onlyStable: false, zoomCoverageChart: false
  }
}

def doPostCoverageSonarStep() {
  if ( env.NODE_NAME ==~ /^x86_64.+/ ) {
    sh "docker load -i ${JENKINS_DOCKER_IMAGE_DIR}/${dockerImageFile}"
  }
  def iC = docker.image("${dockerAgentDockerImage}")
  
  iC.inside(""
  + " -v ${CCACHE_DIR}:${CCACHE_DIR}") {
    sh "cmake --build build --target cppcheck"
    sh """
      sonar-scanner \
        -Dsonar.github.disableInlineComments \
        -Dsonar.github.repository='hyperledger/iroha' \
        -Dsonar.analysis.mode=preview \
        -Dsonar.login=${SONAR_TOKEN} \
        -Dsonar.projectVersion=${BUILD_TAG} \
        -Dsonar.github.oauth=${SORABOT_TOKEN} \
        -Dsonar.github.pullRequest=${CHANGE_ID}
    """
  }
}

return this
