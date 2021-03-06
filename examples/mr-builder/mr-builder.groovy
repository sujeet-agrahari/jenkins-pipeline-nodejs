@Library('jenkins-pipeline-nodejs@featue/simplify') _

env.SLACK_CHANNEL = "ci-merge-requests"
node() {

  // set some defaults values
  def config = [:]
  // these two params can be overwritten from test.cfg.groovy
  config.sshCredentialsId = "jenkins-ssh-key"
  config.dockerImageName = "test-nodejs"
  config.dockerImageTag = "latest"
  config.slackChannel = "ci-merge-requests"
  
  // has to be hard-coded here
  config.gitlabCredentialsId = "jenkins-gitlab-credentials"
  config.workspace = pwd()

  config.envDetails = [:]
  config.envDetails.nodeTestEnv = [NODE_ENV: 'test', NODE_PATH: '.']
  config.envDetails.injectSecretsTest = false
  config.envDetails.vaultTokenSecretId = null
  config.envDetails.vaultAddr = null
  config.envDetails.secretSpecsPath = null
  config.envDetails.debugMode = false

  try {
    gitlabBuilds(builds: ["build docker image", "run tests", "run lint"]) {

      // start of checkout stage
      stage('Checkout') {
        
        sh(script: 'git config --global user.email "you@example.com"')
        sh(script: 'git config --global user.name "Your Name"')
        
        if (!fileExists('repo')){ sh(script: "rm -rf repo && mkdir -p repo") }
        dir('repo') {
          withCredentials([string(credentialsId: config.gitlabCredentialsId, variable: 'credentials')]) {
            checkout(
              changelog: true,
              poll: true,
              scm: [
                $class: 'GitSCM',
                branches: [[name: "origin/${env.gitlabSourceBranch}"]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [
                  [$class: 'CleanBeforeCheckout'],
                  [
                    $class: 'PreBuildMerge',
                    options: [
                      fastForwardMode: 'FF',
                      mergeRemote: 'origin',
                      mergeStrategy: 'DEFAULT',
                      mergeTarget: "${env.gitlabTargetBranch}"]
                    ]
                ],
                submoduleCfg: [],
                userRemoteConfigs: [
                  [
                    name: 'origin',
                    credentialsId: credentials,
                    url: env.gitlabTargetRepoSshUrl
                  ]
                ]
              ]
            )
          }
        }
      }
      // end of checkout stage

      // start of config stage
      stage('read test config') {
        if (fileExists('./repo/test.cfg.groovy')) {
          // overwrite default test config
          config.testConfig = load('./repo/test.cfg.groovy')

          // overwrite default values if specified
          if(config.testConfig.sshCredentialsId) { config.sshCredentialsId = config.testConfig.sshCredentialsId }
          if(config.testConfig.slackChannel) { config.slackChannel =  config.testConfig.slackChannel }
          
          // overwrite envDetails
          if(config.testConfig.nodeTestEnv) { config.envDetails.nodeTestEnv = config.testConfig.nodeTestEnv }
          if(config.testConfig.injectSecretsTest) { config.envDetails.injectSecretsTest = config.testConfig.injectSecretsTest }
          if(config.testConfig.vaultTokenSecretId) { config.envDetails.vaultTokenSecretId = config.testConfig.vaultTokenSecretId }
          if(config.testConfig.secretSpecsPath) { config.envDetails.secretSpecsPath = config.testConfig.secretSpecsPath }
          if(config.testConfig.debugMode) { config.envDetails.debugMode = config.testConfig.debugMode }
          if(config.testConfig.vaultAddr) { config.envDetails.vaultAddr = config.testConfig.vaultAddr }

        } else {
          echo("Test configuration file does not exist.")
        }
      }
      // end of config stage

      // start of build stage
      gitlabCommitStatus(name: "build docker image") {
        stage('Build Docker image') {
          reason='docker image build'
          createNodeComposeBuildEnv(config, './build.json') // create docker-compose file
          sh(script: "docker-compose -f ./build.json build")
        }
      }
      // end of build stage

      // start of test stage
      gitlabCommitStatus(name: "run tests") {
        stage('Run Docker tests') {
          reason='tests'
          createNodeComposeTestEnv(config, './test.json') // create docker-compose file
          try {
            sh(script: "docker-compose -f test.json up --no-start")
            sh(script: "docker-compose -f test.json run main npm run ci-test")
          } finally {
            recordNodeTestResults(true, 100.0)
            recordNodeCoverageResults()
          }
        }
      }
      // end of test stage

      // start of lint stage
      gitlabCommitStatus(name: "run lint") {
        stage('Run Docker lint') {
          reason='lint'
          createNodeComposeLintEnv(config, './lint.json')
          sh(script: "docker-compose -f lint.json up --no-start")
          sh(script: "docker-compose -f lint.json run main npm run ci-lint")

          // set correct path to tested files in the lint results
          sh(script: "sed -i 's#/usr/src/app/#${config.workspace}/repo/#g' ci-outputs/lint/checkstyle-result.xml")
          recordNodeLintResults()

          if (currentBuild.result != 'SUCCESS') {
            error "Lint results are UNSTABLE (reported result is '${currentBuild.result}')"
          }
        }
      }
      // end of lint stage
    }

  } catch (e) {
    currentBuild.result = "FAILURE"
    throw e
  } finally {
    notifyNodeBuild(
      buildStatus: currentBuild.result,
      buildType: 'MR',
      channel: config.slackChannel,
      reason: reason,
      author: 'nobody'
    )

    // try to remove containers after every run
    sh(script: "docker-compose -f test.json rm -s -f")
    sh(script: "docker-compose -f lint.json rm -s -f")
    sh(script: "docker-compose -f build.json rm -s -f")
  }
}
