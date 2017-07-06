#!groovy
// Copyright (c) IBM 2017

/*------------------------
  Typical usage:
  @Library('MicroserviceBuilder') _
  microserviceBuilderPipeline {
    image = 'microservice-test'
  }

  The following parameters may also be specified. Their defaults are shown below.
  These are the names of images to be downloaded from https://hub.docker.com/.

    mavenImage = 'maven:3.5.0-jdk-8'
    dockerImage = 'docker'
    kubectlImage = 'lachlanevenson/k8s-kubectl:v1.6.0'
    mvnCommands = 'clean package'

  You can also specify:

    build = 'true' - any value other than 'true' == false
    deploy = 'true' - any value other than 'true' == false
    test = 'true' - `mvn verify` is run if this value is `true` and a pom.xml exists
    debug = 'false' - namespaces created during tests are deleted unless this value is set to 'true'
    deployBranch = 'master' - only builds from this branch are deployed
    namespace = 'targetNamespace' - deploys into Kubernetes targetNamespace.
      Default is to deploy into Jenkins' namespace.

-------------------------*/

import com.cloudbees.groovy.cps.NonCPS
import java.io.File
import java.util.UUID
import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;

def call(body) {
  def config = [:]
  // Parameter expansion works after the call to body() below.
  // See https://jenkins.io/doc/book/pipeline/shared-libraries/ 'Defining a more structured DSL'
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  print "microserviceBuilderPipeline : config = ${config}"

  def image = config.image
  def maven = (config.mavenImage == null) ? 'maven:3.5.0-jdk-8' : config.mavenImage
  def docker = (config.dockerImage == null) ? 'docker' : config.dockerImage
  def kubectl = (config.kubectlImage == null) ? 'lachlanevenson/k8s-kubectl:v1.6.0' : config.kubectlImage
  def mvnCommands = (config.mvnCommands == null) ? 'clean package' : config.mvnCommands
  def registry = System.getenv("REGISTRY").trim()
  def registrySecret = System.getenv("REGISTRY_SECRET").trim()
  def build = (config.build ?: System.getenv ("BUILD")).trim().toLowerCase() == 'true'
  def deploy = (config.deploy ?: System.getenv ("DEPLOY")).trim().toLowerCase() == 'true'
  def namespace = config.namespace ?: (System.getenv("NAMESPACE") ?: "").trim()

  // 'deploy', 'test' and 'debug' options were all added later. Helm chart may not have the associated properties set. 
  def test = (config.test ?: (System.getenv ("TEST") ?: "false").trim()).toLowerCase() == 'true'
  def debug = (config.debug ?: (System.getenv ("DEBUG") ?: "false").trim()).toLowerCase() == 'true'
  def deployBranch = config.deployBranch ?: ((System.getenv("DEFAULT_DEPLOY_BRANCH") ?: "").trim() ?: 'master')

  print "microserviceBuilderPipeline: registry=${registry} registrySecret=${registrySecret} build=${build} \
  deploy=${deploy} deployBranch=${deployBranch} test=${test} debug=${debug} namespace=${namespace}"

  // We won't be able to get hold of registrySecret if Jenkins is running in a non-default namespace that is not the deployment namespace. 
  // In that case we'll need the registrySecret to have been ported over, perhaps during pipeline install. 

  // Only mount registry secret if it's present
  def volumes = [ hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock') ]
  if (registrySecret) {
    volumes += secretVolume(secretName: registrySecret, mountPath: '/root')
  }
  print "microserviceBuilderPipeline: volumes = ${volumes}"

  testNamespace = "testns-${env.BUILD_ID}-" + UUID.randomUUID()
  print "testing against namespace " + testNamespace

    podTemplate(
    label: 'msbPod',
    containers: [
      containerTemplate(name: 'maven', image: maven, ttyEnabled: true, command: 'cat',
        envVars: [
          containerEnvVar(key: 'KUBERNETES_NAMESPACE', value: testNamespace)
        ]),
      containerTemplate(name: 'docker', image: docker, command: 'cat', ttyEnabled: true,
        envVars: [
          containerEnvVar(key: 'DOCKER_API_VERSION', value: '1.23.0')
        ]),
      containerTemplate(name: 'kubectl', image: kubectl, ttyEnabled: true, command: 'cat'),
    ],
    volumes: volumes
  ){
    node('msbPod') {
      def gitCommit

      stage ('Extract') {
        checkout scm
        gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        echo "checked out git commit ${gitCommit}"
        echo "1: result = ${currentBuild.result}"
      }

      if (build) {
        if (fileExists('pom.xml')) {
          stage ('Maven Build') {
            container ('maven') {
              sh "mvn -B ${mvnCommands}"
            }
          }
        }
        if (fileExists('Dockerfile')) {
          stage ('Docker Build') {
            container ('docker') {
              sh "docker build -t ${image}:${gitCommit} ."
              if (!registry.endsWith('/')) {
                registry = "${registry}/"
              }
              sh "ln -s /root/.dockercfg /home/jenkins/.dockercfg"
              sh "docker tag ${image}:${gitCommit} ${registry}${image}:${gitCommit}"
              sh "docker push ${registry}${image}:${gitCommit}"
            }
          }
        }
        echo "2: result = ${currentBuild.result}"
      }

      /* replace '${image}:latest' with '${registry}{image}:${gitcommit}' in manifests/*
         We'll need this so that we can use manifests/ for test or deployment.
         It's only a local change and not committed back to git. */
      sh "find manifests -type f | xargs sed -i \'s|${image}:latest|${registry}${image}:${gitCommit}|g\'"

      if (test && fileExists('pom.xml')) {
        stage ('Verify') {
          container ('kubectl') {
            sh "kubectl create namespace ${testNamespace}"
            sh "kubectl label namespace ${testNamespace} test=true"

            giveRegistryAccessToNamespace (testNamespace, registrySecret)

            sh "kubectl apply -f manifests --namespace ${testNamespace}"

            
          }
          container ('maven') {
            try {
              sh "mvn -B verify"
              echo "3: result = ${currentBuild.result}"
            } finally {
              step([$class: 'JUnitResultArchiver', allowEmptyResults: true, testResults: '**/target/failsafe-reports/*.xml'])
              echo "3.1: result = ${currentBuild.result}"
              step([$class: 'ArtifactArchiver', artifacts: '**/target/failsafe-reports/*.txt', allowEmptyArchive: true])
              echo "3.2: result = ${currentBuild.result}"
              if (!debug) {
                container ('kubectl') {
                  sh "kubectl delete namespace ${testNamespace}"
                }
              }
              echo "4: result = ${currentBuild.result}"
            }
          }
        }
      }

      if (deploy && env.BRANCH_NAME == deployBranch && fileExists('manifests')) {
        stage ('Deploy') {
          container ('kubectl') {
            def deployCommand = "kubectl apply -f manifests"
            if (namespace) {
              deployCommand += " --namespace ${namespace} "
            }
            sh deployCommand
          }
        }
      }

      echo "5: result = ${currentBuild.result}"

    }
  }
}

/* 
  We have a (temporary) namespace that we want to grant CfC registry access to. 
  String namespace: target namespace
  String registrySecret: secret in Jenkins' namespace to use

  1. Port registrySecret into namespace
  2. Modify 'default' serviceaccount to use ported registrySecret. 
*/

def giveRegistryAccessToNamespace (String namespace, String registrySecret) { 
  String secretScript = "kubectl get secret/${registrySecret} -o jsonpath=\"{.data.\\.dockercfg}\""
  String secret = sh (script: secretScript, returnStdout: true).trim()
  String yaml = """
  apiVersion: v1
  data:
    .dockercfg: ${secret}
  kind: Secret
  metadata:
    name: ${registrySecret}
  type: kubernetes.io/dockercfg
  """
  sh "printf -- \"${yaml}\" | kubectl apply --namespace ${namespace} -f -"

  String sa = sh (script: "kubectl get sa default -o json --namespace ${namespace}", returnStdout: true).trim()
  /*
      JsonSlurper is not thread safe, not serializable, and not good to use in Jenkins jobs. See 
      https://stackoverflow.com/questions/37864542/jenkins-pipeline-notserializableexception-groovy-json-internal-lazymap
  */
  def map = new JsonSlurperClassic().parseText (sa) 
  map.metadata.remove ('resourceVersion')
  map.put ('imagePullSecrets', [['name': registrySecret]])
  def json = JsonOutput.prettyPrint(JsonOutput.toJson(map))
  writeFile file: 'temp.json', text: json
  sh "kubectl replace sa default --namespace ${namespace} -f temp.json"
}
