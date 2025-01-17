package ai.verta.modeldb.project;

import ai.verta.common.Artifact;
import ai.verta.common.ArtifactTypeEnum.ArtifactType;
import ai.verta.common.KeyValue;
import ai.verta.common.ModelDBResourceEnum.ModelDBServiceResourceTypes;
import ai.verta.modeldb.*;
import ai.verta.modeldb.LogProjectCodeVersion.Response;
import ai.verta.modeldb.ProjectServiceGrpc.ProjectServiceImplBase;
import ai.verta.modeldb.artifactStore.ArtifactStoreDAO;
import ai.verta.modeldb.audit_log.AuditLogLocalDAO;
import ai.verta.modeldb.authservice.RoleService;
import ai.verta.modeldb.common.CommonUtils;
import ai.verta.modeldb.common.authservice.AuthService;
import ai.verta.modeldb.common.exceptions.AlreadyExistsException;
import ai.verta.modeldb.common.exceptions.InternalErrorException;
import ai.verta.modeldb.common.exceptions.NotFoundException;
import ai.verta.modeldb.dto.ProjectPaginationDTO;
import ai.verta.modeldb.entities.audit_log.AuditLogLocalEntity;
import ai.verta.modeldb.exceptions.InvalidArgumentException;
import ai.verta.modeldb.experimentRun.ExperimentRunDAO;
import ai.verta.modeldb.utils.ModelDBUtils;
import ai.verta.uac.GetResourcesResponseItem;
import ai.verta.uac.ModelDBActionEnum.ModelDBServiceActions;
import ai.verta.uac.ResourceVisibility;
import ai.verta.uac.ServiceEnum.Service;
import ai.verta.uac.UserInfo;
import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class ProjectServiceImpl extends ProjectServiceImplBase {

  public static final Logger LOGGER = LogManager.getLogger(ProjectServiceImpl.class);
  private final AuthService authService;
  private final RoleService roleService;
  private final ProjectDAO projectDAO;
  private final ExperimentRunDAO experimentRunDAO;
  private final ArtifactStoreDAO artifactStoreDAO;
  private final AuditLogLocalDAO auditLogLocalDAO;
  private static final String SERVICE_NAME =
      String.format("%s.%s", ModelDBConstants.SERVICE_NAME, ModelDBConstants.PROJECT);

  public ProjectServiceImpl(ServiceSet serviceSet, DAOSet daoSet) {
    this.authService = serviceSet.authService;
    this.roleService = serviceSet.roleService;
    this.projectDAO = daoSet.projectDAO;
    this.experimentRunDAO = daoSet.experimentRunDAO;
    this.artifactStoreDAO = daoSet.artifactStoreDAO;
    this.auditLogLocalDAO = daoSet.auditLogLocalDAO;
  }

  private void saveAuditLogs(
      UserInfo userInfo, String action, List<String> resourceIds, String metadataBlob) {
    List<AuditLogLocalEntity> auditLogLocalEntities =
        resourceIds.stream()
            .map(
                resourceId ->
                    new AuditLogLocalEntity(
                        SERVICE_NAME,
                        authService.getVertaIdFromUserInfo(
                            userInfo == null ? authService.getCurrentLoginUserInfo() : userInfo),
                        action,
                        resourceId,
                        ModelDBConstants.PROJECT,
                        Service.MODELDB_SERVICE.name(),
                        metadataBlob))
            .collect(Collectors.toList());
    if (!auditLogLocalEntities.isEmpty()) {
      auditLogLocalDAO.saveAuditLogs(auditLogLocalEntities);
    }
  }

  /**
   * Convert CreateProject request to Project entity and insert in database.
   *
   * @param CreateProject request, CreateProject.Response response
   * @return void
   */
  @Override
  public void createProject(
      CreateProject request, StreamObserver<CreateProject.Response> responseObserver) {
    try {
      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, null, ModelDBServiceActions.CREATE);

      // Get the user info from the Context
      UserInfo userInfo = authService.getCurrentLoginUserInfo();
      Project project = projectDAO.insertProject(request, userInfo);

      saveAuditLogs(
          userInfo, ModelDBConstants.CREATE, Collections.singletonList(project.getId()), "");

      responseObserver.onNext(CreateProject.Response.newBuilder().setProject(project).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, CreateProject.Response.getDefaultInstance());
    }
  }

  /**
   * Update project Description in Project Entity. Create project object with updated data from
   * UpdateProjectDescription request and update in database.
   *
   * @param UpdateProjectDescription request, UpdateProjectDescription.Response response
   * @return void
   */
  @Override
  public void updateProjectDescription(
      UpdateProjectDescription request,
      StreamObserver<UpdateProjectDescription.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        String errorMessage = "Project ID is not found in UpdateProjectDescription request";
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject =
          projectDAO.updateProjectDescription(request.getId(), request.getDescription());
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "update",
              "description",
              updatedProject.getDescription()));
      responseObserver.onNext(
          UpdateProjectDescription.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, UpdateProjectDescription.Response.getDefaultInstance());
    }
  }

  @Override
  public void addProjectAttributes(
      AddProjectAttributes request,
      StreamObserver<AddProjectAttributes.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getAttributesList().isEmpty()) {
        errorMessage = "Project ID and Attribute list not found in AddProjectAttributes request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in AddProjectAttributes request";
      } else if (request.getAttributesList().isEmpty()) {
        errorMessage = "Attribute list not found in AddProjectAttributes request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject =
          projectDAO.addProjectAttributes(request.getId(), request.getAttributesList());
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "add",
              "attribute",
              new Gson().toJson(request.getAttributesList())));
      responseObserver.onNext(
          AddProjectAttributes.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, AddProjectAttributes.Response.getDefaultInstance());
    }
  }

  /**
   * Updates the project Attributes field from the Project Entity.
   *
   * @param UpdateProjectAttributes request, UpdateProjectAttributes.Response response
   * @return void
   */
  @Override
  public void updateProjectAttributes(
      UpdateProjectAttributes request,
      StreamObserver<UpdateProjectAttributes.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getAttribute().getKey().isEmpty()) {
        errorMessage = "Project ID and attribute key not found in UpdateProjectAttributes request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in UpdateProjectAttributes request";
      } else if (request.getAttribute().getKey().isEmpty()) {
        errorMessage = "Attribute key not found in UpdateProjectAttributes request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject =
          projectDAO.updateProjectAttributes(request.getId(), request.getAttribute());
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "update",
              "attribute",
              new Gson().toJson(request.getAttribute())));
      responseObserver.onNext(
          UpdateProjectAttributes.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, UpdateProjectAttributes.Response.getDefaultInstance());
    }
  }

  /**
   * This method provide List<KeyValue> attributes of given projectId in GetProjectAttributes
   * request.
   *
   * @param GetProjectAttributes request, GetProjectAttributes.Response response
   * @return void
   */
  @Override
  public void getProjectAttributes(
      GetAttributes request, StreamObserver<GetAttributes.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty()
          && request.getAttributeKeysList().isEmpty()
          && !request.getGetAll()) {
        errorMessage = "Project ID and Project attribute keys not found in GetAttributes request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in GetAttributes request";
      } else if (request.getAttributeKeysList().isEmpty() && !request.getGetAll()) {
        errorMessage = "Project attribute keys not found in GetAttributes request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.READ);

      List<KeyValue> attributes =
          projectDAO.getProjectAttributes(
              request.getId(), request.getAttributeKeysList(), request.getGetAll());
      responseObserver.onNext(
          GetAttributes.Response.newBuilder().addAllAttributes(attributes).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetAttributes.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteProjectAttributes(
      DeleteProjectAttributes request,
      StreamObserver<DeleteProjectAttributes.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty()
          && request.getAttributeKeysList().isEmpty()
          && !request.getDeleteAll()) {
        errorMessage =
            "Project ID and Project attribute keys not found in DeleteProjectAttributes request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in DeleteProjectAttributes request";
      } else if (request.getAttributeKeysList().isEmpty() && !request.getDeleteAll()) {
        errorMessage = "Project attribute keys not found in DeleteProjectAttributes request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.DELETE);

      Project updatedProject =
          projectDAO.deleteProjectAttributes(
              request.getId(), request.getAttributeKeysList(), request.getDeleteAll());
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "delete",
              "attribute",
              request.getDeleteAll()
                  ? "deleteAll"
                  : new Gson().toJson(request.getAttributeKeysList())));
      responseObserver.onNext(
          DeleteProjectAttributes.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, DeleteProjectAttributes.Response.getDefaultInstance());
    }
  }

  /**
   * Add the Tags in project Tags field.
   *
   * @param AddProjectTags request, AddProjectTags.Response response
   * @return void
   */
  @Override
  public void addProjectTags(
      AddProjectTags request, StreamObserver<AddProjectTags.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        String errorMessage = "Project ID not found in AddProjectTags request";
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject =
          projectDAO.addProjectTags(
              request.getId(), ModelDBUtils.checkEntityTagsLength(request.getTagsList()));
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "add",
              "tags",
              new Gson().toJson(request.getTagsList())));
      responseObserver.onNext(
          AddProjectTags.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, AddProjectTags.Response.getDefaultInstance());
    }
  }

  @Override
  public void getProjectTags(GetTags request, StreamObserver<GetTags.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        String errorMessage = "Project ID not found in GetTags request";
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.READ);

      List<String> tags = projectDAO.getProjectTags(request.getId());
      responseObserver.onNext(GetTags.Response.newBuilder().addAllTags(tags).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetTags.Response.getDefaultInstance());
    }
  }

  /**
   * Delete the project Tags field from the Project Entity.
   *
   * @param DeleteProjectTags request, DeleteProjectTags.Response response
   * @return void
   */
  @Override
  public void deleteProjectTags(
      DeleteProjectTags request, StreamObserver<DeleteProjectTags.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getTagsList().isEmpty() && !request.getDeleteAll()) {
        errorMessage = "Project ID and Project tags not found in DeleteProjectTags request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in DeleteProjectTags request";
      } else if (request.getTagsList().isEmpty() && !request.getDeleteAll()) {
        errorMessage = "Project tags not found in DeleteProjectTags request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject =
          projectDAO.deleteProjectTags(
              request.getId(), request.getTagsList(), request.getDeleteAll());
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "delete",
              "tags",
              request.getDeleteAll() ? "deleteAll" : new Gson().toJson(request.getTagsList())));
      responseObserver.onNext(
          DeleteProjectTags.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, DeleteProjectTags.Response.getDefaultInstance());
    }
  }

  @Override
  public void addProjectTag(
      AddProjectTag request, StreamObserver<AddProjectTag.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getTag().isEmpty()) {
        errorMessage = "Project ID and Project tag not found in AddProjectTag request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in AddProjectTag request";
      } else if (request.getTag().isEmpty()) {
        errorMessage = "Project tag not found in AddProjectTag request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject =
          projectDAO.addProjectTags(
              request.getId(),
              ModelDBUtils.checkEntityTagsLength(Collections.singletonList(request.getTag())));
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(ModelDBConstants.METADATA_JSON_TEMPLATE, "add", "tag", request.getTag()));
      responseObserver.onNext(
          AddProjectTag.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, AddProjectTag.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteProjectTag(
      DeleteProjectTag request, StreamObserver<DeleteProjectTag.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getTag().isEmpty()) {
        errorMessage = "Project ID and Project tag not found in DeleteProjectTag request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in DeleteProjectTag request";
      } else if (request.getTag().isEmpty()) {
        errorMessage = "Project tag not found in DeleteProjectTag request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject =
          projectDAO.deleteProjectTags(request.getId(), Arrays.asList(request.getTag()), false);
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE, "delete", "tag", request.getTag()));
      responseObserver.onNext(
          DeleteProjectTag.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, DeleteProjectTag.Response.getDefaultInstance());
    }
  }

  /**
   * Get ProjectId from DeleteProject request and delete it from database.
   *
   * @param DeleteProject request, DeleteProject.Response response
   * @return void
   */
  @Override
  public void deleteProject(
      DeleteProject request, StreamObserver<DeleteProject.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        String errorMessage = "Project ID not found in DeleteProject request";
        throw new InvalidArgumentException(errorMessage);
      }

      List<String> deletedProjectIds =
          projectDAO.deleteProjects(Collections.singletonList(request.getId()));
      saveAuditLogs(null, ModelDBConstants.DELETE, Collections.singletonList(request.getId()), "");
      responseObserver.onNext(
          DeleteProject.Response.newBuilder().setStatus(!deletedProjectIds.isEmpty()).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, DeleteProject.Response.getDefaultInstance());
    }
  }

  /**
   * Gets all the projects belonging to the user and returns as response. If user auth is not
   * enabled, it returns all the projects from the database.
   *
   * @param GetProjects request, GetProjects.Response response
   * @return void
   */
  @Override
  public void getProjects(
      GetProjects request, StreamObserver<GetProjects.Response> responseObserver) {
    try {
      LOGGER.debug("getting project");
      UserInfo userInfo = authService.getCurrentLoginUserInfo();

      FindProjects.Builder findProjects =
          FindProjects.newBuilder()
              .setPageNumber(request.getPageNumber())
              .setPageLimit(request.getPageLimit())
              .setAscending(request.getAscending())
              .setSortKey(request.getSortKey())
              .setWorkspaceName(request.getWorkspaceName());

      ProjectPaginationDTO projectPaginationDTO =
          projectDAO.findProjects(findProjects.build(), null, userInfo, ResourceVisibility.PRIVATE);

      responseObserver.onNext(
          GetProjects.Response.newBuilder()
              .addAllProjects(projectPaginationDTO.getProjects())
              .setTotalRecords(projectPaginationDTO.getTotalRecords())
              .build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetProjects.Response.getDefaultInstance());
    }
  }

  @Override
  public void getProjectById(
      GetProjectById request, StreamObserver<GetProjectById.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        String errorMessage = "Project ID not found in GetProjectById request";
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.READ);

      Project project = projectDAO.getProjectByID(request.getId());
      responseObserver.onNext(GetProjectById.Response.newBuilder().setProject(project).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetProjectById.Response.getDefaultInstance());
    }
  }

  @Override
  public void getProjectByName(
      GetProjectByName request, StreamObserver<GetProjectByName.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getName().isEmpty()) {
        errorMessage = "Project name is not found in GetProjectByName request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Get the user info from the Context
      UserInfo userInfo = authService.getCurrentLoginUserInfo();
      String workspaceName =
          request.getWorkspaceName().isEmpty()
              ? authService.getUsernameFromUserInfo(userInfo)
              : request.getWorkspaceName();
      List<GetResourcesResponseItem> responseItem =
          roleService.getEntityResourcesByName(
              Optional.of(request.getName()),
              Optional.empty(),
              ModelDBServiceResourceTypes.PROJECT);

      FindProjects.Builder findProjects =
          FindProjects.newBuilder()
              .addAllProjectIds(
                  responseItem.stream()
                      .map(GetResourcesResponseItem::getResourceId)
                      .collect(Collectors.toList()))
              .setWorkspaceName(workspaceName);

      ProjectPaginationDTO projectPaginationDTO =
          projectDAO.findProjects(findProjects.build(), null, userInfo, ResourceVisibility.PRIVATE);

      if (projectPaginationDTO.getTotalRecords() == 0) {
        throw new NotFoundException("Project not found");
      }

      Project selfOwnerProject = null;
      List<Project> sharedProjects = new ArrayList<>();

      for (Project project : projectPaginationDTO.getProjects()) {
        if (userInfo == null
            || project.getOwner().equals(authService.getVertaIdFromUserInfo(userInfo))) {
          selfOwnerProject = project;
        } else {
          sharedProjects.add(project);
        }
      }

      GetProjectByName.Response.Builder responseBuilder = GetProjectByName.Response.newBuilder();
      if (selfOwnerProject != null) {
        responseBuilder.setProjectByUser(selfOwnerProject);
      }
      responseBuilder.addAllSharedProjects(sharedProjects);

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetProjectByName.Response.getDefaultInstance());
    }
  }

  @Override
  public void verifyConnection(
      Empty request, StreamObserver<VerifyConnectionResponse> responseObserver) {
    responseObserver.onNext(VerifyConnectionResponse.newBuilder().setStatus(true).build());
    responseObserver.onCompleted();
  }

  @Override
  public void deepCopyProject(
      DeepCopyProject request, StreamObserver<DeepCopyProject.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId() == null) {
        String errorMessage = "Project ID not found in DeepCopyProject request";
        throw new InvalidArgumentException(errorMessage);
      }

      // Get the user info from the Context
      UserInfo userInfo = authService.getCurrentLoginUserInfo();

      Project project = projectDAO.deepCopyProjectForUser(request.getId(), userInfo);
      saveAuditLogs(null, ModelDBConstants.CREATE, Collections.singletonList(request.getId()), "");
      responseObserver.onNext(DeepCopyProject.Response.newBuilder().setProject(project).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, DeepCopyProject.Response.getDefaultInstance());
    }
  }

  private Map<String, Double[]> getMinMaxMetricsValueMap(
      Map<String, Double[]> minMaxMetricsValueMap, KeyValue keyValue) {
    Double value = keyValue.getValue().getNumberValue();
    Double[] minMaxValueArray = minMaxMetricsValueMap.get(keyValue.getKey());
    if (minMaxValueArray == null) {
      minMaxValueArray = new Double[2]; // Index 0 = minValue, Index 1 = maxValue
    }
    if (minMaxValueArray[0] == null || minMaxValueArray[0] > value) {
      minMaxValueArray[0] = value;
    }
    if (minMaxValueArray[1] == null || minMaxValueArray[1] < value) {
      minMaxValueArray[1] = value;
    }
    minMaxMetricsValueMap.put(keyValue.getKey(), minMaxValueArray);
    return minMaxMetricsValueMap;
  }

  @Override
  public void getSummary(GetSummary request, StreamObserver<GetSummary.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getEntityId().isEmpty()) {
        String errorMessage = "Project ID not found in GetSummary request";
        throw new InvalidArgumentException(errorMessage);
      }

      LOGGER.debug("Getting user info");
      UserInfo userInfo = authService.getCurrentLoginUserInfo();

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getEntityId(), ModelDBServiceActions.READ);

      List<Project> projects =
          projectDAO.getProjects(ModelDBConstants.ID, request.getEntityId(), userInfo);
      if (projects.isEmpty()) {
        String errorMessage = "Project not found for given EntityId";
        throw new NotFoundException(errorMessage);
      } else if (projects.size() != 1) {
        String errorMessage = "Multiple projects found for given EntityId";
        throw new InternalErrorException(errorMessage);
      }
      Project project = projects.get(0);

      Long experimentCount =
          projectDAO.getExperimentCount(Collections.singletonList(project.getId()));
      Long experimentRunCount =
          projectDAO.getExperimentRunCount(Collections.singletonList(project.getId()));

      List<ExperimentRun> experimentRuns =
          experimentRunDAO.getExperimentRuns(
              ModelDBConstants.PROJECT_ID, request.getEntityId(), userInfo);

      LastModifiedExperimentRunSummary lastModifiedExperimentRunSummary = null;
      List<MetricsSummary> minMaxMetricsValueList = new ArrayList<>();
      if (!experimentRuns.isEmpty()) {
        ExperimentRun lastModifiedExperimentRun = null;
        Map<String, Double[]> minMaxMetricsValueMap = new HashMap<>(); // In double[], Index 0 =
        // minValue, Index 1 =
        // maxValue
        Set<String> keySet = new HashSet<>();

        for (ExperimentRun experimentRun : experimentRuns) {
          if (lastModifiedExperimentRun == null
              || lastModifiedExperimentRun.getDateUpdated() < experimentRun.getDateUpdated()) {
            lastModifiedExperimentRun = experimentRun;
          }

          for (KeyValue keyValue : experimentRun.getMetricsList()) {
            keySet.add(keyValue.getKey());
            minMaxMetricsValueMap = getMinMaxMetricsValueMap(minMaxMetricsValueMap, keyValue);
          }
        }

        lastModifiedExperimentRunSummary =
            LastModifiedExperimentRunSummary.newBuilder()
                .setLastUpdatedTime(lastModifiedExperimentRun.getDateUpdated())
                .setName(lastModifiedExperimentRun.getName())
                .build();

        for (String key : keySet) {
          Double[] minMaxValueArray = minMaxMetricsValueMap.get(key); // Index 0 = minValue, Index 1
          // = maxValue
          MetricsSummary minMaxMetricsSummary =
              MetricsSummary.newBuilder()
                  .setKey(key)
                  .setMinValue(minMaxValueArray[0]) // Index 0 =
                  // minValue
                  .setMaxValue(minMaxValueArray[1]) // Index 1 = maxValue
                  .build();
          minMaxMetricsValueList.add(minMaxMetricsSummary);
        }
      }

      GetSummary.Response.Builder responseBuilder =
          GetSummary.Response.newBuilder()
              .setName(project.getName())
              .setLastUpdatedTime(project.getDateUpdated())
              .setTotalExperiment(experimentCount)
              .setTotalExperimentRuns(experimentRunCount)
              .addAllMetrics(minMaxMetricsValueList);

      if (lastModifiedExperimentRunSummary != null) {
        responseBuilder.setLastModifiedExperimentRunSummary(lastModifiedExperimentRunSummary);
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetSummary.Response.getDefaultInstance());
    }
  }

  @Override
  public void setProjectReadme(
      SetProjectReadme request, StreamObserver<SetProjectReadme.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getReadmeText() == null) {
        errorMessage = "Project ID and Project Readme text not found in SetProjectReadme request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in SetProjectReadme request";
      } else if (request.getReadmeText() == null) {
        errorMessage = "Project Readme text not found in SetProjectReadme request";
      }

      if (errorMessage != null) {
        LOGGER.info(errorMessage);
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject =
          projectDAO.updateProjectReadme(request.getId(), request.getReadmeText());
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "update",
              "readme",
              request.getReadmeText()));
      responseObserver.onNext(
          SetProjectReadme.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, SetProjectReadme.Response.getDefaultInstance());
    }
  }

  @Override
  public void getProjectReadme(
      GetProjectReadme request, StreamObserver<GetProjectReadme.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        String errorMessage = "Project ID not found in GetProjectReadme request";
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.READ);

      Project project = projectDAO.getProjectByID(request.getId());
      responseObserver.onNext(
          GetProjectReadme.Response.newBuilder().setReadmeText(project.getReadmeText()).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetProjectReadme.Response.getDefaultInstance());
    }
  }

  @Override
  public void setProjectShortName(
      SetProjectShortName request, StreamObserver<SetProjectShortName.Response> responseObserver) {
    try {
      // Request Parameter Validation
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getShortName().isEmpty()) {
        errorMessage = "Project ID and Project shortName not found in SetProjectShortName request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in SetProjectShortName request";
      } else if (request.getShortName().isEmpty()) {
        errorMessage = "Project shortName not found in SetProjectShortName request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      LOGGER.debug("Getting user info");
      UserInfo userInfo = authService.getCurrentLoginUserInfo();

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      String projectShortName = ModelDBUtils.convertToProjectShortName(request.getShortName());
      if (!projectShortName.equals(request.getShortName())) {
        errorMessage = "Project short name is not valid";
        throw new InternalErrorException(errorMessage);
      }

      Project project =
          projectDAO.setProjectShortName(request.getId(), request.getShortName(), userInfo);
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(project.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "update",
              "short_name",
              project.getShortName()));
      responseObserver.onNext(
          SetProjectShortName.Response.newBuilder().setProject(project).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, SetProjectShortName.Response.getDefaultInstance());
    }
  }

  @Override
  public void getProjectShortName(
      GetProjectShortName request, StreamObserver<GetProjectShortName.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getId().isEmpty()) {
        String errorMessage = "Project ID not found in GetProjectShortName request";
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.READ);

      Project project = projectDAO.getProjectByID(request.getId());
      responseObserver.onNext(
          GetProjectShortName.Response.newBuilder().setShortName(project.getShortName()).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, GetProjectShortName.Response.getDefaultInstance());
    }
  }

  @Override
  public void logProjectCodeVersion(
      LogProjectCodeVersion request, StreamObserver<Response> responseObserver) {
    try {
      /*Parameter validation*/
      String errorMessage = null;
      if (request.getId().isEmpty() && request.getCodeVersion() == null) {
        errorMessage = "Project ID and Code version not found in LogProjectCodeVersion request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in LogProjectCodeVersion request";
      } else if (request.getCodeVersion() == null) {
        errorMessage = "CodeVersion not found in LogProjectCodeVersion request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project existingProject = projectDAO.getProjectByID(request.getId());
      Project updatedProject;
      /*Update Code version*/
      if (!existingProject.getCodeVersionSnapshot().hasCodeArchive()
          && !existingProject.getCodeVersionSnapshot().hasGitSnapshot()) {
        updatedProject =
            projectDAO.logProjectCodeVersion(request.getId(), request.getCodeVersion());
      } else {
        errorMessage = "Code version already logged for project " + existingProject.getId();
        throw new AlreadyExistsException(errorMessage);
      }
      /*Build response*/
      LogProjectCodeVersion.Response.Builder responseBuilder =
          LogProjectCodeVersion.Response.newBuilder().setProject(updatedProject);
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "log",
              "code_version",
              new Gson().toJson(request.getCodeVersion())));
      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, LogProjectCodeVersion.Response.getDefaultInstance());
    }
  }

  @Override
  public void getProjectCodeVersion(
      GetProjectCodeVersion request,
      StreamObserver<GetProjectCodeVersion.Response> responseObserver) {
    try {
      /*Parameter validation*/
      if (request.getId().isEmpty()) {
        String errorMessage = "Project ID not found in GetProjectCodeVersion request";
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      /*Get code version*/
      Project existingProject = projectDAO.getProjectByID(request.getId());
      CodeVersion codeVersion = existingProject.getCodeVersionSnapshot();

      responseObserver.onNext(
          GetProjectCodeVersion.Response.newBuilder().setCodeVersion(codeVersion).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, GetProjectCodeVersion.Response.getDefaultInstance());
    }
  }

  @Override
  public void findProjects(
      FindProjects request, StreamObserver<FindProjects.Response> responseObserver) {
    try {
      /*User validation*/
      // Get the user info from the Context
      UserInfo userInfo = authService.getCurrentLoginUserInfo();

      ProjectPaginationDTO projectPaginationDTO =
          projectDAO.findProjects(request, null, userInfo, ResourceVisibility.PRIVATE);

      responseObserver.onNext(
          FindProjects.Response.newBuilder()
              .addAllProjects(projectPaginationDTO.getProjects())
              .setTotalRecords(projectPaginationDTO.getTotalRecords())
              .build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, FindProjects.Response.getDefaultInstance());
    }
  }

  @Override
  public void getUrlForArtifact(
      GetUrlForArtifact request, StreamObserver<GetUrlForArtifact.Response> responseObserver) {
    try {
      String errorMessage = null;
      if (request.getId().isEmpty()
          && request.getKey().isEmpty()
          && request.getMethod().isEmpty()) {
        errorMessage = "Project ID and Key and Method not found in GetUrlForArtifact request";
      } else if (request.getId().isEmpty()) {
        errorMessage = "Project ID not found in GetUrlForArtifact request";
      } else if (request.getKey().isEmpty()) {
        errorMessage = "Artifact Key not found in GetUrlForArtifact request";
      } else if (request.getMethod().isEmpty()) {
        errorMessage = "Method is not found in GetUrlForArtifact request";
      }

      if (errorMessage != null) {
        throw new InvalidArgumentException(errorMessage);
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.READ);

      String s3Key = null;

      /*Process code*/
      if (request.getArtifactType() == ArtifactType.CODE) {
        // just creating the error string
        errorMessage = "Code versioning artifact not found at project level";
        s3Key = getUrlForCode(request);
      } else {
        errorMessage = "Project level artifacts only supported for code";
        throw new InvalidArgumentException(errorMessage);
      }

      if (s3Key == null) {
        throw new NotFoundException(errorMessage);
      }
      GetUrlForArtifact.Response response =
          artifactStoreDAO.getUrlForArtifact(s3Key, request.getMethod());
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, GetUrlForArtifact.Response.getDefaultInstance());
    }
  }

  private String getUrlForCode(GetUrlForArtifact request)
      throws InvalidProtocolBufferException, ExecutionException, InterruptedException {
    String s3Key = null;
    Project proj = projectDAO.getProjectByID(request.getId());
    if (proj.getCodeVersionSnapshot() != null
        && proj.getCodeVersionSnapshot().getCodeArchive() != null) {
      s3Key = proj.getCodeVersionSnapshot().getCodeArchive().getPath();
    }
    return s3Key;
  }

  @Override
  public void logArtifacts(
      LogProjectArtifacts request, StreamObserver<LogProjectArtifacts.Response> responseObserver) {
    try {
      if (request.getId().isEmpty() && request.getArtifactsList().isEmpty()) {
        throw new InvalidArgumentException(
            "Project ID and Artifacts not found in LogArtifacts request");
      } else if (request.getId().isEmpty()) {
        throw new InvalidArgumentException("Project ID not found in LogArtifacts request");
      } else if (request.getArtifactsList().isEmpty()) {
        throw new InvalidArgumentException("Artifacts not found in LogArtifacts request");
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      List<Artifact> artifactList =
          ModelDBUtils.getArtifactsWithUpdatedPath(request.getId(), request.getArtifactsList());
      Project updatedProject = projectDAO.logArtifacts(request.getId(), artifactList);
      LogProjectArtifacts.Response.Builder responseBuilder =
          LogProjectArtifacts.Response.newBuilder().setProject(updatedProject);
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE,
              "add",
              "artifacts",
              new Gson().toJson(request.getArtifactsList())));
      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, LogProjectArtifacts.Response.getDefaultInstance());
    }
  }

  @Override
  public void getArtifacts(
      GetArtifacts request, StreamObserver<GetArtifacts.Response> responseObserver) {
    try {
      if (request.getId().isEmpty()) {
        throw new InvalidArgumentException("Project ID not found in GetArtifacts request");
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.READ);

      List<Artifact> artifactList = projectDAO.getProjectArtifacts(request.getId());
      responseObserver.onNext(
          GetArtifacts.Response.newBuilder().addAllArtifacts(artifactList).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, GetArtifacts.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteArtifact(
      DeleteProjectArtifact request,
      StreamObserver<DeleteProjectArtifact.Response> responseObserver) {
    try {
      if (request.getId().isEmpty() && request.getKey().isEmpty()) {
        throw new InvalidArgumentException(
            "Project ID and Artifact key not found in DeleteArtifact request");
      } else if (request.getId().isEmpty()) {
        throw new InvalidArgumentException("Project ID not found in DeleteArtifact request");
      } else if (request.getKey().isEmpty()) {
        throw new InvalidArgumentException("Artifact key not found in DeleteArtifact request");
      }

      // Validate if current user has access to the entity or not
      roleService.validateEntityUserWithUserInfo(
          ModelDBServiceResourceTypes.PROJECT, request.getId(), ModelDBServiceActions.UPDATE);

      Project updatedProject = projectDAO.deleteArtifacts(request.getId(), request.getKey());
      saveAuditLogs(
          null,
          ModelDBConstants.UPDATE,
          Collections.singletonList(updatedProject.getId()),
          String.format(
              ModelDBConstants.METADATA_JSON_TEMPLATE, "delete", "artifacts", request.getKey()));
      responseObserver.onNext(
          DeleteProjectArtifact.Response.newBuilder().setProject(updatedProject).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(
          responseObserver, e, DeleteProjectArtifact.Response.getDefaultInstance());
    }
  }

  @Override
  public void deleteProjects(
      DeleteProjects request, StreamObserver<DeleteProjects.Response> responseObserver) {
    try {
      // Request Parameter Validation
      if (request.getIdsList().isEmpty()) {
        throw new InvalidArgumentException("Project IDs not found in DeleteProjects request");
      }

      List<String> deletedProjectIds = projectDAO.deleteProjects(request.getIdsList());
      saveAuditLogs(null, ModelDBConstants.DELETE, deletedProjectIds, "");

      responseObserver.onNext(
          DeleteProjects.Response.newBuilder().setStatus(!deletedProjectIds.isEmpty()).build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      CommonUtils.observeError(responseObserver, e, DeleteProjects.Response.getDefaultInstance());
    }
  }
}
