package org.exoplatform.management.social.operations;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.management.social.SocialExtension;
import org.exoplatform.portal.config.DataStorage;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.model.Dashboard;
import org.exoplatform.portal.mop.importer.ImportMode;
import org.exoplatform.services.organization.Group;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.social.common.RealtimeListAccess;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.application.SpaceActivityPublisher;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.identity.provider.SpaceIdentityProvider;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.model.AvatarAttachment;
import org.exoplatform.social.core.profile.ProfileFilter;
import org.exoplatform.social.core.space.SpaceUtils;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.social.core.storage.api.ActivityStorage;
import org.exoplatform.social.core.storage.api.IdentityStorage;
import org.exoplatform.social.core.storage.cache.CachedActivityStorage;
import org.gatein.common.logging.Logger;
import org.gatein.common.logging.LoggerFactory;
import org.gatein.management.api.ContentType;
import org.gatein.management.api.PathAddress;
import org.gatein.management.api.controller.ManagedRequest;
import org.gatein.management.api.controller.ManagedResponse;
import org.gatein.management.api.controller.ManagementController;
import org.gatein.management.api.exceptions.OperationException;
import org.gatein.management.api.operation.OperationAttachment;
import org.gatein.management.api.operation.OperationAttributes;
import org.gatein.management.api.operation.OperationContext;
import org.gatein.management.api.operation.OperationHandler;
import org.gatein.management.api.operation.OperationNames;
import org.gatein.management.api.operation.ResultHandler;
import org.gatein.management.api.operation.model.NoResultModel;

import com.thoughtworks.xstream.XStream;

/**
 * @author <a href="mailto:bkhanfir@exoplatform.com">Boubaker Khanfir</a>
 * @version $Revision$
 */
public class SocialDataImportResource implements OperationHandler {

  final private static Logger log = LoggerFactory.getLogger(SocialDataImportResource.class);

  final private static int BUFFER = 2048000;

  private OrganizationService organizationService;
  private SpaceService spaceService;
  private IdentityStorage identityStorage;
  private ActivityManager activityManager;
  private IdentityManager identityManager;
  private ManagementController managementController;
  private UserACL userACL;
  private DataStorage dataStorage;
  private ActivityStorage activityStorage;

  @Override
  public void execute(OperationContext operationContext, ResultHandler resultHandler) throws OperationException {
    organizationService = operationContext.getRuntimeContext().getRuntimeComponent(OrganizationService.class);
    spaceService = operationContext.getRuntimeContext().getRuntimeComponent(SpaceService.class);
    managementController = operationContext.getRuntimeContext().getRuntimeComponent(ManagementController.class);
    activityManager = operationContext.getRuntimeContext().getRuntimeComponent(ActivityManager.class);
    identityManager = operationContext.getRuntimeContext().getRuntimeComponent(IdentityManager.class);
    userACL = operationContext.getRuntimeContext().getRuntimeComponent(UserACL.class);
    dataStorage = operationContext.getRuntimeContext().getRuntimeComponent(DataStorage.class);
    activityStorage = operationContext.getRuntimeContext().getRuntimeComponent(ActivityStorage.class);
    identityStorage = operationContext.getRuntimeContext().getRuntimeComponent(IdentityStorage.class);

    OperationAttributes attributes = operationContext.getAttributes();
    List<String> filters = attributes.getValues("filter");

    // "replace-existing" attribute. Defaults to false.
    boolean replaceExisting = filters.contains("replace-existing:true");

    // space-name = spaceDisplayName || spacePrettyName ||
    // spaceOriginalPrettyName (before renaming)
    String spaceName = operationContext.getAddress().resolvePathTemplate("space-name");
    if (spaceName != null) {
      Space space = spaceService.getSpaceByDisplayName(spaceName);
      if (space == null) {
        space = spaceService.getSpaceByPrettyName(spaceName);
        if (space == null) {
          space = spaceService.getSpaceByGroupId(SpaceUtils.SPACE_GROUP + "/" + spaceName);
        }
      }
      if (space != null) {
        if (!replaceExisting) {
          log.info("Space '" + space.getDisplayName() + "' was found but replaceExisting=false. Ignore space '" + spaceName + "' import.");
          resultHandler.completed(NoResultModel.INSTANCE);
          return;
        }
        spaceName = space.getPrettyName();
      }
    }

    File tmpZipFile = null;
    try {
      // Copy attachement to local temporary File
      tmpZipFile = File.createTempFile("staging-social", ".zip");
      Map<String, Map<String, File>> fileToImportByOwner = extractDataFromZipAndCreateSpaces(operationContext.getAttachment(false), spaceName, replaceExisting, tmpZipFile);
      Set<String> spacePrettyNames = fileToImportByOwner.keySet();
      for (String extractedSpacePrettyName : spacePrettyNames) {
        log.info("Importing applications data for space: " + extractedSpacePrettyName + " ...");

        Space space = spaceService.getSpaceByPrettyName(extractedSpacePrettyName);
        if (space == null) {
          continue;
        }

        Map<String, File> spaceFiles = fileToImportByOwner.get(extractedSpacePrettyName);
        Set<String> filesKeys = spaceFiles.keySet();
        List<String> filesKeysList = new ArrayList<String>(filesKeys);
        Collections.sort(filesKeysList, new Comparator<String>() {
          @Override
          public int compare(String o1, String o2) {
            if (o1.contains(SocialDashboardExportTask.FILENAME)) {
              return 1;
            }
            if (o2.contains(SocialDashboardExportTask.FILENAME)) {
              return -1;
            }
            return o1.compareTo(o2);
          }
        });

        for (String fileKey : filesKeysList) {
          File fileToImport = spaceFiles.get(fileKey);
          if (fileKey.equals(SocialExtension.ANSWER_RESOURCE_PATH) || fileKey.equals(SocialExtension.CALENDAR_RESOURCE_PATH) || fileKey.equals(SocialExtension.CONTENT_RESOURCE_PATH)
              || fileKey.equals(SocialExtension.FAQ_RESOURCE_PATH) || fileKey.equals(SocialExtension.FORUM_RESOURCE_PATH) || fileKey.equals(SocialExtension.WIKI_RESOURCE_PATH)
              || fileKey.equals(SocialExtension.SITES_IMPORT_RESOURCE_PATH)) {
            importSubResource(fileToImport, fileKey);
          } else {
            if (fileKey.contains(SpaceActivitiesExportTask.FILENAME)) {
              createActivities(extractedSpacePrettyName, fileToImport);
            } else if (fileToImport.getAbsolutePath().contains(SocialDashboardExportTask.FILENAME)) {
              updateDashboard(space.getGroupId(), fileToImport);
            } else if (fileToImport.getAbsolutePath().contains(SpaceAvatarExportTask.FILENAME)) {
              updateAvatar(space, fileToImport);
            } else {
              log.warn("Cannot handle file: " + fileToImport.getAbsolutePath() + ". Ignore it.");
            }
          }
          try {
            fileToImport.delete();
          } catch (Exception e) {
            log.warn("Cannot delete temporary file from disk: " + fileToImport.getAbsolutePath() + ". It seems we have an opened InputStream. Anyway, it's not blocker.", e);
          }
        }
        log.info("Import operation finished successfully for space: " + extractedSpacePrettyName);
      }
      log.info("Import operation finished successfully.");
    } catch (IOException e) {
      log.warn("Cannot create temporary file.", e);
    } finally {
      if (tmpZipFile != null) {
        try {
          String tempFolderPath = tmpZipFile.getAbsolutePath().replaceAll("\\.zip$", "");
          File tempFolderFile = new File(tempFolderPath);
          if (tempFolderFile.exists()) {
            FileUtils.deleteDirectory(tempFolderFile);
          }
          FileUtils.forceDelete(tmpZipFile);
        } catch (Exception e) {
          log.warn("Unable to delete temp file: " + tmpZipFile.getAbsolutePath() + ". Not blocker.", e);
          tmpZipFile.deleteOnExit();
        }
      }
    }

    resultHandler.completed(NoResultModel.INSTANCE);
  }

  private void deleteSpaceActivities(String extractedSpacePrettyName) {
    if (activityStorage instanceof CachedActivityStorage) {
      ((CachedActivityStorage) activityStorage).clearCache();
    }
    Identity spaceIdentity = identityManager.getOrCreateIdentity(SpaceIdentityProvider.NAME, extractedSpacePrettyName, false);
    RealtimeListAccess<ExoSocialActivity> listAccess = activityManager.getActivitiesOfSpaceWithListAccess(spaceIdentity);
    ExoSocialActivity[] activities = listAccess.load(0, listAccess.getSize());
    for (ExoSocialActivity activity : activities) {
      RealtimeListAccess<ExoSocialActivity> commentsListAccess = activityManager.getCommentsWithListAccess(activity);
      if (commentsListAccess.getSize() > 0) {
        List<ExoSocialActivity> comments = commentsListAccess.loadAsList(0, commentsListAccess.getSize());
        for (ExoSocialActivity commentActivity : comments) {
          activityManager.deleteActivity(commentActivity);
        }
      }
      activityManager.deleteActivity(activity);
    }
  }

  private void updateAvatar(Space space, File fileToImport) {
    FileInputStream inputStream = null;
    try {
      inputStream = new FileInputStream(fileToImport);
      InputStreamReader reader = new InputStreamReader(inputStream, "UTF-8");

      XStream xstream = new XStream();
      AvatarAttachment avatarAttachment = (AvatarAttachment) xstream.fromXML(reader);
      space.setAvatarAttachment(avatarAttachment);

      spaceService.updateSpace(space);
      spaceService.updateSpaceAvatar(space);
    } catch (Exception e) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "Error while updating Space '" + space.getDisplayName() + "' avatar.", e);
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          log.warn("Cannot close input stream: " + fileToImport.getAbsolutePath() + ". Ignore non blocking operation.");
        }
      }
    }
  }

  private void updateDashboard(String spaceGroupId, File fileToImport) {
    FileInputStream inputStream = null;
    try {
      Dashboard dashboard = SocialDashboardExportTask.getDashboard(dataStorage, spaceGroupId);
      if (dashboard == null) {
        return;
      }

      inputStream = new FileInputStream(fileToImport);

      XStream xstream = new XStream();
      Dashboard newDashboard = (Dashboard) xstream.fromXML(inputStream);

      dashboard.setAccessPermissions(newDashboard.getAccessPermissions());
      dashboard.setChildren(newDashboard.getChildren());
      dashboard.setDecorator(newDashboard.getDecorator());
      dashboard.setDescription(newDashboard.getDescription());
      dashboard.setFactoryId(newDashboard.getFactoryId());
      dashboard.setHeight(newDashboard.getHeight());
      dashboard.setIcon(newDashboard.getIcon());
      dashboard.setName(newDashboard.getName());
      dashboard.setTemplate(newDashboard.getTemplate());
      dashboard.setTitle(newDashboard.getTitle());
      dashboard.setWidth(newDashboard.getWidth());

      dataStorage.saveDashboard(newDashboard);
    } catch (Exception e) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "Error while updating Space '" + spaceGroupId + "' dashbord.", e);
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          log.warn("Cannot close input stream: " + fileToImport.getAbsolutePath() + ". Ignore non blocking operation.");
        }
      }
    }
  }

  private void createActivities(String spacePrettyName, File activitiesFile) {
    log.info("Importing space activities");
    FileInputStream inputStream = null;
    try {
      inputStream = new FileInputStream(activitiesFile);

      // Unmarshall metadata xml file
      XStream xstream = new XStream();

      @SuppressWarnings("unchecked")
      List<ExoSocialActivity> activities = (List<ExoSocialActivity>) xstream.fromXML(inputStream);
      List<ExoSocialActivity> activitiesList = new ArrayList<ExoSocialActivity>();
      ProfileFilter profileFilter = new ProfileFilter();
      Identity identity = null;
      for (ExoSocialActivity activity : activities) {
        profileFilter.setName(activity.getUserId());
        identity = getIdentity(profileFilter, null);

        if (identity != null) {
          activity.setUserId(identity.getId());

          profileFilter.setName(activity.getPosterId());
          identity = getIdentity(profileFilter, null);

          if (identity != null) {
            activity.setPosterId(identity.getId());
            activitiesList.add(activity);

            Set<String> keys = activity.getTemplateParams().keySet();
            for (String key : keys) {
              String value = activity.getTemplateParams().get(key);
              if (value != null) {
                activity.getTemplateParams().put(key, StringEscapeUtils.unescapeHtml(value));
              }
            }
            if (StringUtils.isNotEmpty(activity.getTitle())) {
              activity.setTitle(StringEscapeUtils.unescapeHtml(activity.getTitle()));
            }
            if (StringUtils.isNotEmpty(activity.getBody())) {
              activity.setBody(StringEscapeUtils.unescapeHtml(activity.getBody()));
            }
            if (StringUtils.isNotEmpty(activity.getSummary())) {
              activity.setSummary(StringEscapeUtils.unescapeHtml(activity.getSummary()));
            }
          }
          activity.setReplyToId(null);
          String[] commentedIds = activity.getCommentedIds();
          if (commentedIds != null && commentedIds.length > 0) {
            for (int i = 0; i < commentedIds.length; i++) {
              profileFilter.setName(commentedIds[i]);
              identity = getIdentity(profileFilter, null);
              if (identity != null) {
                commentedIds[i] = identity.getId();
              }
            }
            activity.setCommentedIds(commentedIds);
          }
          String[] mentionedIds = activity.getMentionedIds();
          if (mentionedIds != null && mentionedIds.length > 0) {
            for (int i = 0; i < mentionedIds.length; i++) {
              profileFilter.setName(mentionedIds[i]);
              identity = getIdentity(profileFilter, null);
              if (identity != null) {
                mentionedIds[i] = identity.getId();
              }
            }
            activity.setMentionedIds(mentionedIds);
          }
          String[] likeIdentityIds = activity.getLikeIdentityIds();
          if (likeIdentityIds != null && likeIdentityIds.length > 0) {
            for (int i = 0; i < likeIdentityIds.length; i++) {
              profileFilter.setName(likeIdentityIds[i]);
              identity = getIdentity(profileFilter, null);
              if (identity != null) {
                likeIdentityIds[i] = identity.getId();
              }
            }
            activity.setLikeIdentityIds(likeIdentityIds);
          }
        } else {
          log.warn("Space activity : '" + activity.getTitle() + "' isn't imported because its user/space wasn't found.");
        }
      }
      Identity spaceIdentity = identityManager.getOrCreateIdentity(SpaceIdentityProvider.NAME, spacePrettyName, false);
      ExoSocialActivity parentActivity = null;
      for (ExoSocialActivity exoSocialActivity : activitiesList) {
        exoSocialActivity.setId(null);
        if (exoSocialActivity.isComment()) {
          if (parentActivity == null) {
            log.warn("Attempt to add Social activity comment to a null activity");
          } else {
            saveComment(parentActivity, exoSocialActivity);
          }
        } else {
          parentActivity = null;
          saveActivity(exoSocialActivity, spaceIdentity);
          if (SpaceActivityPublisher.SPACE_PROFILE_ACTIVITY.equals(exoSocialActivity.getType()) || SpaceActivityPublisher.USER_ACTIVITIES_FOR_SPACE.equals(exoSocialActivity.getType())) {
            identityStorage.updateProfileActivityId(spaceIdentity, exoSocialActivity.getId(), Profile.AttachedActivityType.SPACE);
          }
          parentActivity = activityManager.getActivity(exoSocialActivity.getId());
        }
      }
    } catch (FileNotFoundException e) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "Cannot find extracted file: " + activitiesFile.getAbsolutePath(), e);
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          log.warn("Cannot close input stream: " + activitiesFile.getAbsolutePath() + ". Ignore non blocking operation.");
        }
      }
    }
  }

  private void saveActivity(ExoSocialActivity activity, Identity spaceIdentity) {
    long updatedTime = activity.getUpdated().getTime();
    activityManager.saveActivityNoReturn(spaceIdentity, activity);
    activity.setUpdated(updatedTime);
    activityManager.updateActivity(activity);
  }

  private void saveComment(ExoSocialActivity activity, ExoSocialActivity comment) {
    long updatedTime = activity.getUpdated().getTime();
    activityManager.saveComment(activity, comment);
    activity.setUpdated(updatedTime);
    activityManager.updateActivity(activity);
  }

  private Identity getIdentity(ProfileFilter profileFilter, String providerId) {
    String computedProviderId = providerId;
    if (computedProviderId == null) {
      computedProviderId = OrganizationIdentityProvider.NAME;
    }
    ListAccess<Identity> identities = identityManager.getIdentitiesByProfileFilter(computedProviderId, profileFilter, false);
    try {
      if (identities.getSize() > 0) {
        return identities.load(0, 1)[0];
      } else if (providerId == null) {
        return getIdentity(profileFilter, SpaceIdentityProvider.NAME);
      }
    } catch (Exception e) {
      log.error(e);
    }
    return null;
  }

  private boolean createOrReplaceSpace(String spacePrettyName, String targetSpaceName, boolean replaceExisting, InputStream inputStream) throws Exception {
    // Unmarshall metadata xml file
    XStream xstream = new XStream();
    xstream.alias("metadata", SpaceMetaData.class);
    SpaceMetaData spaceMetaData = (SpaceMetaData) xstream.fromXML(inputStream);
    String newSpacePrettyName = spaceMetaData.getPrettyName();
    String oldSpacePrettyName = spaceMetaData.getGroupId().replace(SpaceUtils.SPACE_GROUP + "/", "");

    // If selected space doesn't fit the found space, ignore it
    if (targetSpaceName != null && !(targetSpaceName.equals(spaceMetaData.getDisplayName()) || targetSpaceName.equals(oldSpacePrettyName) || targetSpaceName.equals(newSpacePrettyName))) {
      return true;
    }

    Space space = spaceService.getSpaceByGroupId(spaceMetaData.getGroupId());
    if (space != null) {
      if (replaceExisting) {
        log.info("Delete space: '" + spaceMetaData.getPrettyName() + "'.");
        String groupId = space.getGroupId();
        spaceService.deleteSpace(space);
        // FIXME deleting a space don't delete the corresponding group
        Group group = organizationService.getGroupHandler().findGroupById(groupId);
        if (group != null) {
          organizationService.getGroupHandler().removeGroup(group, true);
        }

        // FIXME Answer Bug: deleting a space don't delete answers category, but
        // it will be deleted if answer data is imported
      } else {
        log.info("Space '" + space.getDisplayName() + "' was found but replaceExisting=false. Ignore space import.");
        return true;
      }
    }
    log.info("Create new space: '" + spaceMetaData.getPrettyName() + "'.");
    space = new Space();

    boolean isRenamed = !newSpacePrettyName.equals(oldSpacePrettyName);

    space.setPrettyName(oldSpacePrettyName);
    if (isRenamed) {
      space.setDisplayName(oldSpacePrettyName);
    } else {
      space.setDisplayName(spaceMetaData.getDisplayName());
    }
    space.setGroupId(spaceMetaData.getGroupId());
    space.setTag(spaceMetaData.getTag());
    space.setApp(spaceMetaData.getApp());
    space.setEditor(spaceMetaData.getEditor() != null ? spaceMetaData.getEditor() : spaceMetaData.getManagers().length > 0 ? spaceMetaData.getManagers()[0] : userACL.getSuperUser());
    space.setManagers(spaceMetaData.getManagers());
    space.setInvitedUsers(spaceMetaData.getInvitedUsers());
    space.setRegistration(spaceMetaData.getRegistration());
    space.setDescription(spaceMetaData.getDescription());
    space.setType(spaceMetaData.getType());
    space.setVisibility(spaceMetaData.getVisibility());
    space.setPriority(spaceMetaData.getPriority());
    space.setUrl(spaceMetaData.getUrl());
    spaceService.createSpace(space, space.getEditor());
    if (isRenamed) {
      spaceService.renameSpace(space, spaceMetaData.getDisplayName().trim());
    }
    deleteSpaceActivities(spaceMetaData.getPrettyName());
    return false;
  }

  private void importSubResource(File tempFile, String subResourcePath) {
    Map<String, List<String>> attributesMap = new HashMap<String, List<String>>();
    attributesMap.put("filter", Collections.singletonList("replace-existing:true"));
    attributesMap.put("importMode", Collections.singletonList(ImportMode.OVERWRITE.name()));

    // This will be closed in sub resources, don't close it here
    InputStream inputStream = null;
    try {
      inputStream = new FileInputStream(tempFile);
      ManagedRequest request = ManagedRequest.Factory.create(OperationNames.IMPORT_RESOURCE, PathAddress.pathAddress(subResourcePath), attributesMap, inputStream, ContentType.ZIP);
      ManagedResponse response = managementController.execute(request);
      Object model = response.getResult();
      if (!(model instanceof NoResultModel)) {
        throw new OperationException(OperationNames.IMPORT_RESOURCE, "Unknown error while importing to path: " + subResourcePath);
      }
    } catch (FileNotFoundException e) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "Cannot find extracted file: " + tempFile.getAbsolutePath(), e);
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          log.warn("Cannot close input stream: " + tempFile + ". Ignore non blocking operation.");
        }
      }
    }
  }

  private Map<String, Map<String, File>> extractDataFromZipAndCreateSpaces(OperationAttachment attachment, String spaceName, boolean replaceExisting, File tmpZipFile) throws OperationException {
    if (attachment == null || attachment.getStream() == null) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "No attachment available for Social import.");
    }
    InputStream inputStream = attachment.getStream();
    try {
      copyAttachementToLocalFolder(inputStream, tmpZipFile);

      // Organize File paths by id and extract files from zip to a temp
      // folder and create spaces
      return extractFilesByIdAndCreateSpaces(tmpZipFile, spaceName, replaceExisting);
    } catch (Exception e) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "Error occured while handling attachement", e);
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          log.warn("Cannot close input stream.");
        }
      }
    }
  }

  private void copyAttachementToLocalFolder(InputStream attachmentInputStream, File tmpZipFile) throws IOException, FileNotFoundException {
    NonCloseableZipInputStream zis = null;
    try {
      FileOutputStream tmpFileOutputStream = new FileOutputStream(tmpZipFile);
      IOUtils.copy(attachmentInputStream, tmpFileOutputStream);
      tmpFileOutputStream.close();
      attachmentInputStream.close();
    } finally {
      if (zis != null) {
        try {
          zis.reallyClose();
        } catch (IOException e) {
          log.warn("Can't close inputStream of attachement.");
        }
      }
    }
  }

  private Map<String, Map<String, File>> extractFilesByIdAndCreateSpaces(File tmpZipFile, String targetSpaceName, boolean replaceExisting) throws Exception {
    // Get path of folder where to unzip files
    String targetFolderPath = tmpZipFile.getAbsolutePath().replaceAll("\\.zip$", "") + "/";

    // Open an input stream on local zip file
    NonCloseableZipInputStream zis = new NonCloseableZipInputStream(new FileInputStream(tmpZipFile));

    Map<String, Map<String, File>> filesToImportByOwner = new HashMap<String, Map<String, File>>();
    List<String> ignoredSpaces = new ArrayList<String>();
    try {
      Map<String, ZipOutputStream> zipOutputStreamMap = new HashMap<String, ZipOutputStream>();
      String managedEntryPathPrefix = "social/space/";
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        String zipEntryPath = entry.getName();
        // Skip entries not managed by this extension
        if (!zipEntryPath.startsWith(managedEntryPathPrefix)) {
          continue;
        }

        // Skip directories
        if (entry.isDirectory()) {
          // Create directory in unzipped folder location
          createFile(new File(targetFolderPath + replaceSpecialChars(zipEntryPath)), true);
          continue;
        }
        int idBeginIndex = ("social/space/").length();
        String spacePrettyName = zipEntryPath.substring(idBeginIndex, zipEntryPath.indexOf("/", idBeginIndex));
        if (ignoredSpaces.contains(spacePrettyName)) {
          continue;
        }

        if (!filesToImportByOwner.containsKey(spacePrettyName)) {
          filesToImportByOwner.put(spacePrettyName, new HashMap<String, File>());
        }
        Map<String, File> ownerFiles = filesToImportByOwner.get(spacePrettyName);

        log.info("Receiving content " + zipEntryPath);

        if (zipEntryPath.contains(SocialExtension.ANSWER_RESOURCE_PATH)) {
          putSubResourceEntry(tmpZipFile, targetFolderPath, zis, zipOutputStreamMap, zipEntryPath, spacePrettyName, ownerFiles, SocialExtension.ANSWER_RESOURCE_PATH);
        } else if (zipEntryPath.contains(SocialExtension.CALENDAR_RESOURCE_PATH)) {
          putSubResourceEntry(tmpZipFile, targetFolderPath, zis, zipOutputStreamMap, zipEntryPath, spacePrettyName, ownerFiles, SocialExtension.CALENDAR_RESOURCE_PATH);
        } else if (zipEntryPath.contains(SocialExtension.CONTENT_RESOURCE_PATH)) {
          putSubResourceEntry(tmpZipFile, targetFolderPath, zis, zipOutputStreamMap, zipEntryPath, spacePrettyName, ownerFiles, SocialExtension.CONTENT_RESOURCE_PATH);
        } else if (zipEntryPath.contains(SocialExtension.FAQ_RESOURCE_PATH)) {
          putSubResourceEntry(tmpZipFile, targetFolderPath, zis, zipOutputStreamMap, zipEntryPath, spacePrettyName, ownerFiles, SocialExtension.FAQ_RESOURCE_PATH);
        } else if (zipEntryPath.contains(SocialExtension.FORUM_RESOURCE_PATH)) {
          putSubResourceEntry(tmpZipFile, targetFolderPath, zis, zipOutputStreamMap, zipEntryPath, spacePrettyName, ownerFiles, SocialExtension.FORUM_RESOURCE_PATH);
        } else if (zipEntryPath.contains(SocialExtension.WIKI_RESOURCE_PATH)) {
          putSubResourceEntry(tmpZipFile, targetFolderPath, zis, zipOutputStreamMap, zipEntryPath, spacePrettyName, ownerFiles, SocialExtension.WIKI_RESOURCE_PATH);
        } else if (zipEntryPath.contains(SocialExtension.GROUP_SITE_RESOURCE_PATH)) {
          putSubResourceEntry(tmpZipFile, targetFolderPath, zis, zipOutputStreamMap, zipEntryPath, spacePrettyName, ownerFiles, SocialExtension.SITES_IMPORT_RESOURCE_PATH);
        } else {
          String localFilePath = targetFolderPath + replaceSpecialChars(zipEntryPath);
          if (localFilePath.endsWith(SpaceMetadataExportTask.FILENAME)) {
            // Create space here to be sure that it's created before importing
            // other application resources
            boolean toIgnore = createOrReplaceSpace(spacePrettyName, targetSpaceName, replaceExisting, zis);
            if (toIgnore) {
              ignoredSpaces.add(spacePrettyName);
              filesToImportByOwner.remove(spacePrettyName);
            }
          } else {
            ownerFiles.put(zipEntryPath, new File(localFilePath));

            // Put file Export file in temp folder
            copyToDisk(zis, localFilePath);
          }
        }
        zis.closeEntry();
      }

      Collection<ZipOutputStream> zipOutputStreams = zipOutputStreamMap.values();
      for (ZipOutputStream zipOutputStream : zipOutputStreams) {
        zipOutputStream.close();
      }
    } finally {
      try {
        zis.reallyClose();
      } catch (Exception e) {
        log.warn("Cannot delete temporary file " + tmpZipFile + ". Ignore it.");
      }
    }

    return filesToImportByOwner;
  }

  private void putSubResourceEntry(File tmpZipFile, String targetFolderPath, NonCloseableZipInputStream zis, Map<String, ZipOutputStream> zipOutputStreamMap, String zipEntryPath,
      String spacePrettyName, Map<String, File> ownerFiles, String subResourcePath) throws IOException, FileNotFoundException {
    if (!ownerFiles.containsKey(subResourcePath)) {
      createFile(new File(targetFolderPath + "social/space/" + spacePrettyName + subResourcePath), true);
      String zipFilePath = targetFolderPath + "social/space/" + spacePrettyName + subResourcePath + ".zip";
      ownerFiles.put(subResourcePath, new File(zipFilePath));
      ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFilePath));
      zipOutputStreamMap.put(spacePrettyName + subResourcePath, zos);
    }
    ZipOutputStream zos = zipOutputStreamMap.get(spacePrettyName + subResourcePath);
    String subResourceZipEntryPath = zipEntryPath.replace("social/space/" + spacePrettyName + "/", "");
    zos.putNextEntry(new ZipEntry(subResourceZipEntryPath));
    IOUtils.copy(zis, zos);
    zos.closeEntry();
  }

  private static void copyToDisk(InputStream input, String output) throws Exception {
    byte data[] = new byte[BUFFER];
    BufferedOutputStream dest = null;
    try {
      FileOutputStream fileOuput = new FileOutputStream(createFile(new File(output), false));
      dest = new BufferedOutputStream(fileOuput, BUFFER);
      int count = 0;
      while ((count = input.read(data, 0, BUFFER)) != -1)
        dest.write(data, 0, count);
    } finally {
      if (dest != null) {
        dest.close();
      }
    }
  }

  private static String replaceSpecialChars(String name) {
    return name.replaceAll(":", "_");
  }

  private static File createFile(File file, boolean folder) throws IOException {
    if (file.getParentFile() != null)
      createFile(file.getParentFile(), true);
    if (file.exists())
      return file;
    if (file.isDirectory() || folder)
      file.mkdir();
    else
      file.createNewFile();
    return file;
  }

  // Bug in SUN's JDK XMLStreamReader implementation closes the underlying
  // stream when
  // it finishes reading an XML document. This is no good when we are using
  // a
  // ZipInputStream.
  // See http://bugs.sun.com/view_bug.do?bug_id=6539065 for more
  // information.
  public static class NonCloseableZipInputStream extends ZipInputStream {
    public NonCloseableZipInputStream(InputStream inputStream) {
      super(inputStream);
    }

    @Override
    public void close() throws IOException {}

    private void reallyClose() throws IOException {
      super.close();
    }
  }

}
