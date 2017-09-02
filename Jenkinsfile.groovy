try {
    timeout(time: 10, unit: 'MINUTES') {

        node("maven") {

            stage("Checkout") {
                git url: "https://github.com/leandroberetta/openshift-cicd-demo", branch: "master"
            }

            stage("Compile") {
                sh "mvn clean package -DskipTests -Popenshift"
            }

            stage("Test") {
                sh "mvn test"
            }

            def version = getVersionFromPom("pom.xml")

            stage("Build Image") {
                def newTag = "TestingCandidate-${version}"

                sh "oc project test"
                sh "oc start-build app --from-file=./target/ROOT.war -n test"

                openshiftVerifyBuild bldCfg: "app",
                        namespace: "test"

                openshiftTag alias: "false",
                        destStream: "app",
                        destTag: newTag,
                        destinationNamespace: "test",
                        namespace: "test",
                        srcStream: "app",
                        srcTag: "latest",
                        verbose: "true"
            }

            stage("Deploy to Test") {
                sh "oc project test"
                sh "oc patch dc app --patch '{\"spec\": { \"triggers\": [ { \"type\": \"ImageChange\", \"imageChangeParams\": { \"containerNames\": [ \"app\" ], \"from\": { \"kind\": \"ImageStreamTag\", \"namespace\": \"test\", \"name\": \"app:TestingCandidate-$version\"}}}]}}' -n test"

                openshiftDeploy depCfg: "app",
                        namespace: "test"
            }

            stage("Integration Test") {
                // Complete this section with your actual integration tests

                def newTag = "ProdReady-${version}"

                openshiftTag alias: "false", destStream: "app", destTag: newTag, destinationNamespace: "test", namespace: "test", srcStream: "app", srcTag: "latest", verbose: "false"
            }

            stage('Deploy Pre-Prod?') {
                input "Deploy new version to Pre-Production?"
            }

            // Blue/Green

            def dest = "app-green"
            def active = ""

            stage("Deploy to Pre-Prod") {

                sh "oc project prod"
                sh "oc get route blue-green -n prod -o jsonpath='{ .spec.to.name }' > active"

                active = readFile("active").trim()

                if (active == "app-green")
                    dest = "app-blue"

                sh "oc patch dc ${dest} --patch '{\"spec\": { \"triggers\": [ { \"type\": \"ImageChange\", \"imageChangeParams\": { \"containerNames\": [ \"$dest\" ], \"from\": { \"kind\": \"ImageStreamTag\", \"namespace\": \"test\", \"name\": \"app:ProdReady-$version\"}}}]}}' -n prod"

                openshiftDeploy depCfg: dest,
                        namespace: 'prod'
            }

            stage("Switch Version?") {
                input "Switch to new version?"
            }

            sh "oc patch route blue-green -n prod -p '{ \"spec\" : { \"to\" : { \"name\" : \"$dest\" } } }'"

            stage("Finish Deployment") {
                rollback = input message: "Rollback to previous version?",
                        parameters: [choice(choices: "Yes\nNo", description: '', name: 'Rollback')]
            }

            if (rollback.equals('Yes')) {
                sh "oc patch route blue-green -n prod -p '{ \"spec\" : { \"to\" : {\"name\" : \"$active\" } } }'"
                echo "Deployment rolled back successfully"
            } else {
                echo "Deployment finished successfully"
            }
        }
    }
} catch (err) {
    echo "Caught: ${err}"
    currentBuild.result = 'FAILURE'

    throw err
}

def getVersionFromPom(pom) {
    def matcher = readFile(pom) =~ '<version>(.+)</version>'
    matcher ? matcher[0][1] : null
}