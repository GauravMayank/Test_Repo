#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import java.net.URL
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
//define var for job
def BRANCH_NAME = env.BRANCH_NAME
def TAG = env.tag
def worker_job = env.worker_job
def PRECOMPILE_ENV = env.PRECOMPILE_ENV
//def retry-max-time = env.retry-max-time

def git_clone() {
   stage name: 'app clone repo', concurrency: 5
   //checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:elarahq/'+GIT_REPO+'.git', credentialsId: 'b5b5b230-4f8a-4213-a6ba-7efccc0ae00c' ]], branches: [[name: TAG]]], poll: false
   checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:GauravMayank/'+GIT_REPO+'.git', credentialsId: 'test_key-git-ssh' ]], branches: 'master'], poll: false  
}
def release_job() {
  stage name: 'release', concurrency: 5
   rules:
    - if: $CI_COMMIT_TAG
      when: never                                  
    - if: $CI_COMMIT_BRANCH == 'master'  
  script:
    - echo "running release_job for $TAG"
  release:                                         
    tag_name: 'v0.$CI_PIPELINE_IID'               
    description: 'v0.$CI_PIPELINE_IID'
    ref: '$CI_COMMIT_SHA'
}
}


def build() {
  stage name: 'build docker', concurrency: 5
  try {
     sh "docker build -t ${APP_NAME}:${build_id} -f ${Dockerfile} --build-arg PRECOMPILE_ENV=$PRECOMPILE_ENV ."
     println "++++++++++++++++++docker build done+++++++++++++"
    sh "docker tag  ${APP_NAME}:${build_id}  ${imagetag}"
     return "done"
  } catch (all) {
    throw new hudson.AbortException("Some issue with deployment doker build")
  }
}

def push_artifact() {
  stage name: 'push image to ecr', concurrency: 5
  try {
    sh '''
    #!/bin/bash
    echo "we are pushing"
    a=`/usr/bin/aws ecr get-login --region ap-southeast-1`
    login=`echo $a | sed -e "s/none//g" | sed -e "s/-e//g"`
    $login
    '''
    sh "docker push ${imagetag}"
    println "++++++++++++++++docker push done++++++++++++++++"
  } catch (all) {
    throw new hudson.AbortException("Some issue with  docker push to ecr")
  }
 }
 def argo_clone() {
    stage name: 'clone argo repo', concurrency: 5
    dir('argocode') {
      git ([url: 'git@github.com:elarahq/housing.argo.git', branch: 'master', changelog: true, poll: true])
    }
}
def updateimage() {
  stage name: 'updatetask in git-file', concurrency: 5
  try {
    sh '''
    #!/bin/bash
    cd ${WORKSPACE}/argocode/apps/overlays/${env_name}/${APP_NAME}/
    oldimage=`cat kustomization.yaml | grep newTag | awk '{print$2}'`
    echo "old image in kustomization.yaml"
    echo $oldimage
    echo "replcaing in file"
    echo $newtag
    sed -e "s#${oldimage}#${newtag}#g" kustomization.yaml
    sed -i "s#${oldimage}#${newtag}#g" kustomization.yaml
    '''
    println "++++++++++++++++ Image Updated ++++++++++++++++"
  } catch (all) {
    throw new hudson.AbortException("Some issue with  image update in  git file")
  }
 }
def clean_artifact() {
  stage name: 'clean image', concurrency: 5
  try {
    sh '''
    #!/bin/bash
    echo "we are deleting images"
    docker rmi -f $(docker images -a -q)
   '''
  } catch (all) {
    println "++++++++++++++++no docker to  delete++++++++++++++++"
  }
}
def argosync(){
  stage name:'update app version'
       sh '''
       export ARGOCD_SERVER="gamma-argocd.housing.com"
       /usr/local/bin/argocd --grpc-web app  set $APP_NAME --kustomize-image housing-image=${imagetag} --path apps/overlays/${env_name}/${APP_NAME}
       /usr/local/bin/argocd --grpc-web app  sync $APP_NAME
       '''
}
def sync(){
  stage name:'rotate pod have same tag'
       sh '''
       export ARGOCD_SERVER="gamma-argocd.housing.com"
       buildtime=`date +"%Y-%m-%dT%H:%M:%S""Z"`
       echo $buildtime
       /usr/local/bin/argocd --grpc-web app patch-resource  $APP_NAME  --kind Deployment --resource-name ${env_name}-${APP_NAME}-housing --patch '{"spec": { "template":{ "metadata": { "creationTimestamp": "'${buildtime}'"} }}}' --patch-type 'application/strategic-merge-patch+json'
       '''
}
def updategit() {
  stage name: 'commit in git', concurrency: 5
  try {
    sh '''
    #!/bin/bash
    cd ${WORKSPACE}/argocode
    #/usr/bin/git add apps/overlays/${env_name}/
    /usr/bin/git add apps/overlays/${env_name}/${APP_NAME}/kustomization.yaml
    /usr/bin/git add apps/overlays/${env_name}/${APP_NAME}-migration/kustomization.yaml
    /usr/bin/git commit -m "jenkins"
    /usr/bin/git push origin master
    '''
    println "++++++++++++++++done++++++++++++++++"
  } catch (all) {
    println "++++++++++++++++no object or file to commit++++++++++++++++"
  }
 }

// Deployment Approval
def Deployment_Approval(){
  stage name: 'Deployment_Approval'
  input "Do you want to proceed for main-app deployment?"
}

// Migration code

def migrationsync(){
  stage name:'migration sync'
       sh '''
       export ARGOCD_SERVER="gamma-argocd.housing.com"
       /usr/local/bin/argocd --grpc-web app  set ${APP_NAME}-migration --kustomize-image housing-image=${imagetag} --path apps/overlays/${env_name}/${APP_NAME}-migration
       /usr/local/bin/argocd --grpc-web app  sync ${APP_NAME}-migration
       sleep 30
       echo "Migration sync sleeped 30 sec"
       '''
}

def migrationbuild(){
//def migrationbuild(migration_job, tag){
  migrationsync()
  stage name: 'migration_app_check'
  //println "job run ${migration_job}"
  //println "${TAG}"
  //build job: 'k8s-regions-migration-build', parameters: [[$class: 'StringParameterValue', name: 'tag', value: "${tag}"]],propagate: true, wait: true
   //build job: "${migration_job}", parameters: [[$class: 'StringParameterValue', name: 'tag', value: "${tag}"]],propagate: true, wait: true
  try {
    sh '''
    #!/bin/bash
    #sleep 120
    #migrated_app=`/usr/bin/curl --retry-max-time 7800 --max-time 1 --retry 250 --retry-delay 40 --fail -s -o /dev/null -w "%{http_code}" -s -o /dev/null -w "%{http_code}" "http://${migration_url}"`
    migrated_app=`/usr/bin/curl --retry-max-time ${retry_max_time} --max-time 1 --retry 250 --retry-delay 40 --fail -s -o /dev/null -w "%{http_code}" -s -o /dev/null -w "%{http_code}" "http://${migration_url}"`
    echo "Migration health url Testing : ${migration_url}"
    if [ "$migrated_app" != "200" ]
    then
     echo "migration is not successful"
     exit 1
     fi
    '''
    println "++++++++++++++++ Migration done successfully ++++++++++++++++"
  } catch (all) {
    throw new hudson.AbortException("Some issue with migration healthcheck")
  }
}

def updatemigrationimage() {
  stage name: 'updatetask in migration git-file', concurrency: 5
  try {
    sh '''
    #!/bin/bash
    cd ${WORKSPACE}/argocode/apps/overlays/${env_name}/${APP_NAME}-migration/
    Oldimage=`cat kustomization.yaml | grep newTag | awk '{print$2}'`
    echo "old image in kustomization.yaml"
    echo $Oldimage
    echo "replcaing in file"
    echo $newtag
    sed -e "s#${Oldimage}#${newtag}#g" kustomization.yaml
    sed -i "s#${Oldimage}#${newtag}#g" kustomization.yaml
    '''
    println "++++++++++++++++ Migration image updated ++++++++++++++++"
  } catch (all) {
    throw new hudson.AbortException("Some issue with migration image update in  git file")
  }
 }

// Migration code completed

// Worker job deploy
def workerbuild(){
  stage name: 'worker_build'
  println "job run ${worker_job}"
  build job: "${worker_job}",propagate: true, wait: true
}

node("dev-mini-housing-jenkins-slave") {
     if("${env.revert}" == "true"){
      echo "revert the build"
      env.newtag="${env.revert_id}"
      echo "${newtag}"
      env.imagetag="628119511333.dkr.ecr.ap-southeast-1.amazonaws.com/housing/${repo}:${newtag}"
      withCredentials([string(credentialsId: "argo", variable: 'ARGOCD_AUTH_TOKEN')]) {
       argosync()
      }
      argo_clone()
      updateimage()
      updategit()
     } else if("${env.reload}" == "true"){
      withCredentials([string(credentialsId: "argo", variable: 'ARGOCD_AUTH_TOKEN')]) {
       sync()
      }
     }
       else if("${env.Initiate_migration}" == "true"){
       env.newtag="${TAG}"
       echo "${newtag}"
       env.imagetag="628119511333.dkr.ecr.ap-southeast-1.amazonaws.com/housing/${repo}:${TAG}"
       git_clone()
       build()
       push_artifact()
       echo "migration job with tag : ${TAG}"
       echo "now we are migration with same tag"
       //migrationbuild(migration_job, TAG)
       withCredentials([string(credentialsId: "argo", variable: 'ARGOCD_AUTH_TOKEN')]) {
      // migrationsync()
       //}
       migrationbuild()
       }
       //Deployment_Approval()
       withCredentials([string(credentialsId: "argo", variable: 'ARGOCD_AUTH_TOKEN')]) {
       argosync()
      }
      argo_clone()
      updatemigrationimage()
      updateimage()
      updategit()
      clean_artifact()

     } else {
      env.newtag="${TAG}"
      echo "${newtag}"
      env.imagetag="628119511333.dkr.ecr.ap-southeast-1.amazonaws.com/housing/${repo}:${TAG}"
      git_clone()
      build()
      push_artifact()
      withCredentials([string(credentialsId: "argo", variable: 'ARGOCD_AUTH_TOKEN')]) {
       argosync()
      }
      argo_clone()
      updateimage()
      updategit()
      clean_artifact()
     }
   
    if("${env.Initiate_Worker_Job}" == "true"){
        echo "Worker job is runnign"
        workerbuild()
      }
 }
