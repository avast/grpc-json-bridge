[![TeamCity](https://teamcity.int.avast.com/app/rest/builds/buildType:backends_JvmLibs_GrpcUtils_BuildTest,branch:<default>/statusIcon)](https://teamcity.int.avast.com/project.html?projectId=backends_JvmLibs_GrpcUtils)

# gRPC utils

Set of classes to help us work with gRPC

## GrpcJsonWrapper

Provide HTTP JSON API by gRPC proto contract.

### Setup and start http server
For example we have proto file  with definition of `ControllerService` and gRPC service implementation `ControllerApiGrpcService`.

    val wrapper = new InProcessJsonWrapperBuilder(classOf[ControllerServiceGrpc], new ControllerApiGrpcService(flowService, stateStore))
    val httpServer = new GrpcYapWrapperServerBuilder("localhost", 8080, wrapper).start()

