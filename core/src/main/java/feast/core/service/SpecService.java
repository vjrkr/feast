/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2019 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.core.service;

import static feast.common.models.Store.isSubscribedToFeatureSet;
import static feast.core.validators.Matchers.checkValidCharacters;
import static feast.core.validators.Matchers.checkValidCharactersAllowAsterisk;

import com.google.protobuf.InvalidProtocolBufferException;
import feast.core.dao.EntityRepository;
import feast.core.dao.FeatureSetRepository;
import feast.core.dao.ProjectRepository;
import feast.core.dao.StoreRepository;
import feast.core.exception.RegistrationException;
import feast.core.exception.RetrievalException;
import feast.core.model.*;
import feast.core.validators.EntityValidator;
import feast.core.validators.FeatureSetValidator;
import feast.proto.core.CoreServiceProto.ApplyEntityResponse;
import feast.proto.core.CoreServiceProto.ApplyFeatureSetResponse;
import feast.proto.core.CoreServiceProto.ApplyFeatureSetResponse.Status;
import feast.proto.core.CoreServiceProto.GetEntityRequest;
import feast.proto.core.CoreServiceProto.GetEntityResponse;
import feast.proto.core.CoreServiceProto.GetFeatureSetRequest;
import feast.proto.core.CoreServiceProto.GetFeatureSetResponse;
import feast.proto.core.CoreServiceProto.ListEntitiesRequest;
import feast.proto.core.CoreServiceProto.ListEntitiesResponse;
import feast.proto.core.CoreServiceProto.ListFeatureSetsRequest;
import feast.proto.core.CoreServiceProto.ListFeatureSetsResponse;
import feast.proto.core.CoreServiceProto.ListFeaturesRequest;
import feast.proto.core.CoreServiceProto.ListFeaturesResponse;
import feast.proto.core.CoreServiceProto.ListStoresRequest;
import feast.proto.core.CoreServiceProto.ListStoresResponse;
import feast.proto.core.CoreServiceProto.ListStoresResponse.Builder;
import feast.proto.core.CoreServiceProto.UpdateFeatureSetStatusRequest;
import feast.proto.core.CoreServiceProto.UpdateFeatureSetStatusResponse;
import feast.proto.core.CoreServiceProto.UpdateStoreRequest;
import feast.proto.core.CoreServiceProto.UpdateStoreResponse;
import feast.proto.core.EntityProto;
import feast.proto.core.FeatureSetProto;
import feast.proto.core.FeatureSetProto.FeatureSetStatus;
import feast.proto.core.SourceProto;
import feast.proto.core.StoreProto;
import feast.proto.core.StoreProto.Store.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facilitates management of specs within the Feast registry. This includes getting existing specs
 * and registering new specs.
 */
@Slf4j
@Service
public class SpecService {

  private final EntityRepository entityRepository;
  private final FeatureSetRepository featureSetRepository;
  private final ProjectRepository projectRepository;
  private final StoreRepository storeRepository;
  private final Source defaultSource;

  @Autowired
  public SpecService(
      EntityRepository entityRepository,
      FeatureSetRepository featureSetRepository,
      StoreRepository storeRepository,
      ProjectRepository projectRepository,
      Source defaultSource) {
    this.entityRepository = entityRepository;
    this.featureSetRepository = featureSetRepository;
    this.storeRepository = storeRepository;
    this.projectRepository = projectRepository;
    this.defaultSource = defaultSource;
  }

  /**
   * Get an entity matching the entity name and set project. The entity name and project are
   * required. If the project is omitted, the default would be used.
   *
   * @param request GetEntityRequest Request
   * @return Returns a GetEntityResponse containing an entity
   */
  public GetEntityResponse getEntity(GetEntityRequest request) {
    String projectName = request.getProject();
    String entityName = request.getName();

    if (entityName.isEmpty()) {
      throw new IllegalArgumentException("No entity name provided");
    }
    // Autofill default project if project is not specified
    if (projectName.isEmpty()) {
      projectName = Project.DEFAULT_NAME;
    }

    checkValidCharacters(projectName, "project");
    checkValidCharacters(entityName, "entity");

    EntityV2 entity = entityRepository.findEntityByNameAndProject_Name(entityName, projectName);

    if (entity == null) {
      throw new RetrievalException(
          String.format("Entity with name \"%s\" could not be found.", entityName));
    }

    // Build GetEntityResponse
    GetEntityResponse response = GetEntityResponse.newBuilder().setEntity(entity.toProto()).build();

    return response;
  }

  /**
   * Get a feature set matching the feature name and version and project. The feature set name and
   * project are required, but version can be omitted by providing 0 for its value. If the version
   * is omitted, the latest feature set will be provided. If the project is omitted, the default
   * would be used.
   *
   * @param request: GetFeatureSetRequest Request containing filter parameters.
   * @return Returns a GetFeatureSetResponse containing a feature set..
   */
  public GetFeatureSetResponse getFeatureSet(GetFeatureSetRequest request)
      throws InvalidProtocolBufferException {
    FeatureSet featureSet = getFeatureSet(request.getProject(), request.getName());

    return GetFeatureSetResponse.newBuilder().setFeatureSet(featureSet.toProto()).build();
  }

  private FeatureSet getFeatureSet(String projectName, String featureSetName) {
    // Validate input arguments
    checkValidCharacters(featureSetName, "featureset");

    if (featureSetName.isEmpty()) {
      throw new IllegalArgumentException("No feature set name provided");
    }
    // Autofill default project if project is not specified
    if (projectName.isEmpty()) {
      projectName = Project.DEFAULT_NAME;
    }

    FeatureSet featureSet;

    featureSet =
        featureSetRepository.findFeatureSetByNameAndProject_Name(featureSetName, projectName);

    if (featureSet == null) {
      throw new RetrievalException(
          String.format("Feature set with name \"%s\" could not be found.", featureSetName));
    }
    return featureSet;
  }

  /**
   * Return a list of feature sets matching the feature set name, project and labels provided in the
   * filter. All fields are required. Use '*' in feature set name and project, and empty map in
   * labels in order to return all feature sets in all projects.
   *
   * <p>Project name can be explicitly provided, or an asterisk can be provided to match all
   * projects. It is not possible to provide a combination of asterisks/wildcards and text. If the
   * project name is omitted, the default project would be used.
   *
   * <p>The feature set name in the filter accepts an asterisk as a wildcard. All matching feature
   * sets will be returned. Regex is not supported. Explicitly defining a feature set name is not
   * possible if a project name is not set explicitly
   *
   * <p>The labels in the filter accepts a map. All feature sets which contain every provided label
   * will be returned.
   *
   * @param filter filter containing the desired featureSet name
   * @return ListFeatureSetsResponse with list of featureSets found matching the filter
   */
  public ListFeatureSetsResponse listFeatureSets(ListFeatureSetsRequest.Filter filter)
      throws InvalidProtocolBufferException {
    String name = filter.getFeatureSetName();
    String project = filter.getProject();
    Map<String, String> labelsFilter = filter.getLabelsMap();
    FeatureSetStatus statusFilter = filter.getStatus();

    if (name.isEmpty()) {
      throw new IllegalArgumentException(
          "Invalid listFeatureSetRequest, missing arguments. Must provide feature set name:");
    }

    checkValidCharactersAllowAsterisk(name, "featureset");
    checkValidCharactersAllowAsterisk(project, "project");

    // Autofill default project if project not specified
    if (project.isEmpty()) {
      project = Project.DEFAULT_NAME;
    }

    List<FeatureSet> featureSets = new ArrayList<FeatureSet>() {};

    if (project.contains("*")) {
      // Matching a wildcard project
      if (name.contains("*")) {
        featureSets =
            featureSetRepository.findAllByNameLikeAndProject_NameLikeOrderByNameAsc(
                name.replace('*', '%'), project.replace('*', '%'));
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Invalid listFeatureSetRequest. Feature set name must be set to "
                    + "\"*\" if the project name and feature set name aren't set explicitly: \n%s",
                filter.toString()));
      }
    } else if (!project.contains("*")) {
      // Matching a specific project
      if (name.contains("*")) {
        // Find all feature sets matching a pattern in a specific project
        featureSets =
            featureSetRepository.findAllByNameLikeAndProject_NameOrderByNameAsc(
                name.replace('*', '%'), project);

      } else if (!name.contains("*")) {
        // Find a specific feature set in a specific project
        FeatureSet featureSet =
            featureSetRepository.findFeatureSetByNameAndProject_Name(name, project);
        if (featureSet != null) {
          featureSets.add(featureSet);
        }
      }
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Invalid listFeatureSetRequest. Project name cannot be a pattern. It may only be"
                  + "a specific project name or an asterisk: \n%s",
              filter.toString()));
    }

    ListFeatureSetsResponse.Builder response = ListFeatureSetsResponse.newBuilder();
    if (featureSets.size() > 0) {
      featureSets =
          featureSets.stream()
              .filter(
                  featureSet ->
                      statusFilter.equals(FeatureSetStatus.STATUS_INVALID)
                          || featureSet.getStatus().equals(statusFilter))
              .filter(featureSet -> featureSet.hasAllLabels(labelsFilter))
              .collect(Collectors.toList());
      for (FeatureSet featureSet : featureSets) {
        response.addFeatureSets(featureSet.toProto());
      }
    }

    return response.build();
  }

  /**
   * Return a map of feature references and features matching the project, labels and entities
   * provided in the filter. All fields are required.
   *
   * <p>Project name must be explicitly provided or if the project name is omitted, the default
   * project would be used. A combination of asterisks/wildcards and text is not allowed.
   *
   * <p>The entities in the filter accepts a list. All matching features will be returned. Regex is
   * not supported. If no entities are provided, features will not be filtered by entities.
   *
   * <p>The labels in the filter accepts a map. All matching features will be returned. Regex is not
   * supported. If no labels are provided, features will not be filtered by labels.
   *
   * @param filter filter containing the desired project name, entities and labels
   * @return ListEntitiesResponse with map of feature references and features found matching the
   *     filter
   */
  public ListFeaturesResponse listFeatures(ListFeaturesRequest.Filter filter) {
    try {
      String project = filter.getProject();
      List<String> entities = filter.getEntitiesList();
      Map<String, String> labels = filter.getLabelsMap();

      checkValidCharactersAllowAsterisk(project, "project");

      // Autofill default project if project not specified
      if (project.isEmpty()) {
        project = Project.DEFAULT_NAME;
      }

      // Currently defaults to all FeatureSets
      List<FeatureSet> featureSets =
          featureSetRepository.findAllByNameLikeAndProject_NameOrderByNameAsc("%", project);

      ListFeaturesResponse.Builder response = ListFeaturesResponse.newBuilder();
      if (entities.size() > 0) {
        featureSets =
            featureSets.stream()
                .filter(featureSet -> featureSet.hasAllEntities(entities))
                .collect(Collectors.toList());
      }

      Map<String, Feature> featuresMap;
      for (FeatureSet featureSet : featureSets) {
        featuresMap = featureSet.getFeaturesByRef(labels);
        for (Map.Entry<String, Feature> entry : featuresMap.entrySet()) {
          response.putFeatures(entry.getKey(), entry.getValue().toProto());
        }
      }

      return response.build();
    } catch (InvalidProtocolBufferException e) {
      throw io.grpc.Status.NOT_FOUND
          .withDescription("Unable to retrieve features")
          .withCause(e)
          .asRuntimeException();
    }
  }

  /**
   * Return a list of entities matching the entity name, project and labels provided in the filter.
   * All fields are required. Use '*' in entity name and project, and empty map in labels in order
   * to return all entities in all projects.
   *
   * <p>Project name can be explicitly provided, or an asterisk can be provided to match all
   * projects. It is not possible to provide a combination of asterisks/wildcards and text. If the
   * project name is omitted, the default project would be used.
   *
   * <p>The entity name in the filter accepts an asterisk as a wildcard. All matching entities will
   * be returned. Regex is not supported. Explicitly defining an entity name is not possible if a
   * project name is not set explicitly.
   *
   * <p>The labels in the filter accepts a map. All entities which contain every provided label will
   * be returned.
   *
   * @param filter Filter containing the desired entity name, project and labels
   * @return ListEntitiesResponse with list of entities found matching the filter
   */
  public ListEntitiesResponse listEntities(ListEntitiesRequest.Filter filter) {
    String project = filter.getProject();
    Map<String, String> labelsFilter = filter.getLabelsMap();

    // Autofill default project if project not specified
    if (project.isEmpty()) {
      project = Project.DEFAULT_NAME;
    }

    checkValidCharacters(project, "project");

    List<EntityV2> entities = entityRepository.findAllByProject_Name(project);

    ListEntitiesResponse.Builder response = ListEntitiesResponse.newBuilder();
    if (entities.size() > 0) {
      entities =
          entities.stream()
              .filter(entity -> entity.hasAllLabels(labelsFilter))
              .collect(Collectors.toList());
      for (EntityV2 entity : entities) {
        response.addEntities(entity.toProto());
      }
    }

    return response.build();
  }

  /** Update FeatureSet's status by given FeatureSetReference and new status */
  public UpdateFeatureSetStatusResponse updateFeatureSetStatus(
      UpdateFeatureSetStatusRequest request) {
    FeatureSet featureSet =
        getFeatureSet(request.getReference().getProject(), request.getReference().getName());

    featureSet.setStatus(request.getStatus());
    featureSetRepository.saveAndFlush(featureSet);

    return UpdateFeatureSetStatusResponse.newBuilder().build();
  }

  /**
   * Get stores matching the store name provided in the filter. If the store name is not provided,
   * the method will return all stores currently registered to Feast.
   *
   * @param filter filter containing the desired store name
   * @return ListStoresResponse containing list of stores found matching the filter
   */
  @Transactional
  public ListStoresResponse listStores(ListStoresRequest.Filter filter) {
    try {
      String name = filter.getName();
      if (name.equals("")) {
        Builder responseBuilder = ListStoresResponse.newBuilder();
        for (Store store : storeRepository.findAll()) {
          responseBuilder.addStore(store.toProto());
        }
        return responseBuilder.build();
      }
      Store store =
          storeRepository
              .findById(name)
              .orElseThrow(
                  () ->
                      new RetrievalException(
                          String.format("Store with name '%s' not found", name)));
      return ListStoresResponse.newBuilder().addStore(store.toProto()).build();
    } catch (InvalidProtocolBufferException e) {

      throw io.grpc.Status.NOT_FOUND
          .withDescription("Unable to retrieve stores")
          .withCause(e)
          .asRuntimeException();
    }
  }

  /**
   * Creates or updates an entity in the repository.
   *
   * <p>This function is idempotent. If no changes are detected in the incoming entity's schema,
   * this method will return the existing entity stored in the repository. If project is not
   * specified, the entity will be assigned to the 'default' project.
   *
   * @param newEntitySpec EntitySpecV2 that will be used to create or update an Entity.
   * @param projectName Project namespace of Entity which is to be created/updated
   */
  @Transactional
  public ApplyEntityResponse applyEntity(
      EntityProto.EntitySpecV2 newEntitySpec, String projectName) {
    // Autofill default project if not specified
    if (projectName == null || projectName.isEmpty()) {
      projectName = Project.DEFAULT_NAME;
    }

    // Validate incoming entity
    EntityValidator.validateSpec(newEntitySpec);

    // Find project or create new one if it does not exist
    Project project = projectRepository.findById(projectName).orElse(new Project(projectName));

    // Ensure that the project retrieved from repository is not archived
    if (project.isArchived()) {
      throw new IllegalArgumentException(String.format("Project is archived: %s", projectName));
    }

    // Retrieve existing Entity
    EntityV2 entity =
        entityRepository.findEntityByNameAndProject_Name(newEntitySpec.getName(), projectName);

    EntityProto.Entity newEntity = EntityProto.Entity.newBuilder().setSpec(newEntitySpec).build();
    if (entity == null) {
      // Create new entity since it doesn't exist
      entity = EntityV2.fromProto(newEntity);
    } else {
      // If the entity remains unchanged, we do nothing.
      if (entity.toProto().getSpec().equals(newEntitySpec)) {
        return ApplyEntityResponse.newBuilder().setEntity(entity.toProto()).build();
      }
      entity.updateFromProto(newEntity, projectName);
    }

    // Persist the EntityV2 object
    project.addEntity(entity);
    projectRepository.saveAndFlush(project);

    // Build ApplyEntityResponse
    ApplyEntityResponse response =
        ApplyEntityResponse.newBuilder().setEntity(entity.toProto()).build();
    return response;
  }

  /**
   * Creates or updates a feature set in the repository.
   *
   * <p>This function is idempotent. If no changes are detected in the incoming featureSet's schema,
   * this method will update the incoming featureSet spec with the latest version stored in the
   * repository, and return that. If project is not specified in the given featureSet, will assign
   * the featureSet to the'default' project.
   *
   * @param newFeatureSet Feature set that will be created or updated.
   */
  @Transactional
  public ApplyFeatureSetResponse applyFeatureSet(FeatureSetProto.FeatureSet newFeatureSet)
      throws InvalidProtocolBufferException {
    // Autofill default project if not specified
    if (newFeatureSet.getSpec().getProject().isEmpty()) {
      newFeatureSet =
          newFeatureSet
              .toBuilder()
              .setSpec(newFeatureSet.getSpec().toBuilder().setProject(Project.DEFAULT_NAME).build())
              .build();
    }

    String projectName = newFeatureSet.getSpec().getProject();
    String featureSetName = newFeatureSet.getSpec().getName();
    List<Boolean> isSubscribedToStores = new ArrayList<>() {};
    for (Store store : storeRepository.findAll()) {
      List<Subscription> subscriptionList = store.getSubscriptions();
      boolean isSubscribed =
          isSubscribedToFeatureSet(subscriptionList, projectName, featureSetName);
      isSubscribedToStores.add(isSubscribed);
    }
    // Only throw error if FeatureSet is not subscribed by ALL stores
    if (!isSubscribedToStores.isEmpty()
        && isSubscribedToStores.stream().allMatch(x -> x == false)) {
      throw new RegistrationException(
          String.format(
              "The supplied Project and FeatureSet, %s/%s is either not subscribed or blacklisted and is not available for registration. "
                  + "Please ask your administrator to update subscription in store configuration on serving layer.",
              projectName, featureSetName));
    }

    // Validate incoming feature set
    FeatureSetValidator.validateSpec(newFeatureSet);

    // Find project or create new one if it does not exist
    String project_name = newFeatureSet.getSpec().getProject();
    Project project =
        projectRepository
            .findById(newFeatureSet.getSpec().getProject())
            .orElse(new Project(project_name));

    // Ensure that the project retrieved from repository is not archived
    if (project.isArchived()) {
      throw new IllegalArgumentException(String.format("Project is archived: %s", project_name));
    }

    // Set source to default if not set in proto
    if (newFeatureSet.getSpec().getSource() == SourceProto.Source.getDefaultInstance()) {
      newFeatureSet =
          newFeatureSet
              .toBuilder()
              .setSpec(
                  newFeatureSet.getSpec().toBuilder().setSource(defaultSource.toProto()).build())
              .build();
    }

    // Retrieve existing FeatureSet
    FeatureSet featureSet =
        featureSetRepository.findFeatureSetByNameAndProject_Name(
            newFeatureSet.getSpec().getName(), project_name);

    Status status;
    if (featureSet == null) {
      // Create new feature set since it doesn't exist
      newFeatureSet = newFeatureSet.toBuilder().setSpec(newFeatureSet.getSpec()).build();
      featureSet = FeatureSet.fromProto(newFeatureSet);
      status = Status.CREATED;
    } else {
      // If the featureSet remains unchanged, we do nothing.
      if (featureSet.toProto().getSpec().equals(newFeatureSet.getSpec())) {
        return ApplyFeatureSetResponse.newBuilder()
            .setFeatureSet(featureSet.toProto())
            .setStatus(Status.NO_CHANGE)
            .build();
      }
      featureSet.updateFromProto(newFeatureSet);
      status = Status.UPDATED;
    }

    featureSet.incVersion();

    // Persist the FeatureSet object
    featureSet.setStatus(FeatureSetStatus.STATUS_PENDING);
    project.addFeatureSet(featureSet);
    projectRepository.saveAndFlush(project);

    // Build ApplyFeatureSetResponse
    return ApplyFeatureSetResponse.newBuilder()
        .setFeatureSet(featureSet.toProto())
        .setStatus(status)
        .build();
  }

  /**
   * Sets project to 'default' if project is not specified in feature set
   *
   * @param featureSet Feature set which needs to be imputed with default project.
   */
  public FeatureSetProto.FeatureSet imputeProjectName(FeatureSetProto.FeatureSet featureSet) {
    if (featureSet.getSpec().getProject().isEmpty()) {
      return featureSet
          .toBuilder()
          .setSpec(featureSet.getSpec().toBuilder().setProject(Project.DEFAULT_NAME).build())
          .build();
    }
    return featureSet;
  }

  /**
   * UpdateStore updates the repository with the new given store.
   *
   * @param updateStoreRequest containing the new store definition
   * @return UpdateStoreResponse containing the new store definition
   */
  @Transactional
  public UpdateStoreResponse updateStore(UpdateStoreRequest updateStoreRequest)
      throws InvalidProtocolBufferException {
    StoreProto.Store newStoreProto = updateStoreRequest.getStore();

    List<Subscription> subs = newStoreProto.getSubscriptionsList();
    for (Subscription sub : subs) {
      // Ensure that all fields in a subscription contain values
      if ((sub.getName().isEmpty()) || sub.getProject().isEmpty()) {
        throw new IllegalArgumentException(
            String.format("Missing parameter in subscription: %s", sub));
      }
    }
    Store existingStore = storeRepository.findById(newStoreProto.getName()).orElse(null);

    // Do nothing if no change
    if (existingStore != null && existingStore.toProto().equals(newStoreProto)) {
      return UpdateStoreResponse.newBuilder()
          .setStatus(UpdateStoreResponse.Status.NO_CHANGE)
          .setStore(updateStoreRequest.getStore())
          .build();
    }

    Store newStore = Store.fromProto(newStoreProto);
    storeRepository.save(newStore);
    return UpdateStoreResponse.newBuilder()
        .setStatus(UpdateStoreResponse.Status.UPDATED)
        .setStore(updateStoreRequest.getStore())
        .build();
  }
}
