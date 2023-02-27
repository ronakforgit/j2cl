
Build & Test j2cl on Harness CI
=======================================
This is a fork of [google/j2cl](https://github.com/google/j2cl). This project is used for testing Harness CI's capabilities by the CME team at Harness. This file contains instructions on how to run google/j2cl on Harness CI.


- [Harness Fast CI Blog Announcement](https://harness.io/blog/announcing-speed-enhancements-and-hosted-builds-for-harness-ci)
- [Get Started with Harness CI](https://harness.io/products/continuous-integration)

## Setting up this pipeline on Harness CI Hosted Builds

1. Fork [this repository](https://github.com/google/j2cl) into your GitHub account. 

2. If you are new to Harness CI, signup for [Harness CI](https://app.harness.io/auth/#/signup)
 * Select the `Continuous Integration` module.
 * Follow the below steps to set up your pipeline:-
 
   1. Click on `Create Pipeline`. 
   2. Give a `Name` to your pipeline.
   3. Optionally, you can provide a `Description` and `Tags`
   4. Select `Remote` option on `How do you want to setup your pipeline`.
   5. Provide your `Git Connector`.
    
      - Select `Connectors` -> Click on `+ New Connector`
      - Connector Type: `Github`
      - Name: `j2cl`
      - URL Type : `Repository`
      - Connection Type: `HTTP`
      - GitHub Repository URL: Paste the link of your forked repository
      - Username: (Your Github Username)
      - Personal Access Token: [Check out how to create personal access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token)
      - Secret Name: `Git-Token` 
      - Secret  Value: PAT value generated in Github
      - Click on `Save`.
      - This will allow the repository to be fetched click on it and click `Apply Selected`.
      - Make Enable API access (ON) with the secret token created
      - Click on `Connect through Harness Platform`.
      - To develop more understanding on Connectors check out the [docs](https://developer.harness.io/docs/platform/connectors/add-a-git-hub-connector/).

   6. Select your `Repository` here `j2cl`.
   7. `Git Branch` will automatically be fetched. 
   8. Provide your `YAML Path` as `.harness/{PIPELINE_NAME}.yml`.The root folder `.harness` is required. 
   > _NOTE: Here `.harness/{PIPELINE_NAME}.yml`, {PIPELINE_NAME} is name of your `Pipeline`._
   9. Select `Start`.

3. Add the pipeline.yaml below `tags` in the YAML editor:

```yaml
  properties:
    ci:
      codebase:
        connectorRef: account.gitforronak
        repoName: google/j2cl
        build: <+input>
  stages:
    - stage:
        name: build-ubantu
        identifier: buildubantu
        type: CI
        spec:
          cloneCodebase: true
          platform:
            os: Linux
            arch: Amd64
          runtime:
            type: Cloud
            spec: {}
          execution:
            steps:
              - step:
                  type: Action
                  name: Setup Java
                  identifier: Setup_Java
                  spec:
                    uses: actions/setup-java@v3
                    with:
                      java-version: "11"
                      distribution: zulu
                      java-package: jdk
              - step:
                  type: RestoreCacheGCS
                  name: "restore repo cache "
                  identifier: restore_repo_cache
                  spec:
                    connectorRef: ronakgcpbucket
                    bucket: migration-artifact-comman
                    key: bazel-v5-cache
                    archiveFormat: Tar
                  when:
                    stageStatus: Success
                    condition: "false"
                  failureStrategies:
                    - onFailure:
                        errors:
                          - AllErrors
                        action:
                          type: Ignore
              - step:
                  type: Run
                  name: Configure bazel
                  identifier: Configure_bazel
                  spec:
                    shell: Sh
                    command: |2-
                                echo "build --repository_cache=~/bazel-repository-cache" >> ~/.bazelrc
                                echo "build --disk_cache=~/bazel-action-cache" >> ~/.bazelrc
                                echo "build --verbose_failures" >> ~/.bazelrc
                                ./build_test.sh CI
              - step:
                  type: SaveCacheGCS
                  name: save bazel repo cache
                  identifier: save_bazel_repo_cache
                  spec:
                    connectorRef: ronakgcpbucket
                    bucket: migration-artifact-comman
                    key: bazel-v5-cache
                    sourcePaths:
                      - /home/ubuntu/.cache/bazel/
                    archiveFormat: Tar
                  when:
                    stageStatus: Success
                    condition: "false"
                  failureStrategies: []
          sharedPaths:
            - /home/ubuntu/

```

> _NOTE: Make sure you modify the connectors with the connectors you create._

4.  Go to the newly created pipeline and hit the `Triggers` tab. If everything went well, you should see two triggers auto-created. A `Pull Request` trigger and a `Push` trigger. For this exercise, we only need `Pull Request` trigger to be enabled.

5. Create a Pull Request in a new branch by updating the project. (e.g. add a comment or new line). This should invoke a built-in Harness CI

6. Merge the PR after the pipeline execution is successful.

7. Enable GitHub Actions: The repository forked in _**Step 2**_ already has a GitHub Actions workflow file added. You can choose to enable this workflow from the Actions tab on GitHub.

8. Create any other Pull Request with a few source or test file changes. You can consider cherry-picking any of the commits from the main repository.

9. This PR will trigger the Harness CI pipeline (as well as GitHub Actions workflow if enabled in Step-7). Here we are implementing a single workflow for GitHub Actions.
