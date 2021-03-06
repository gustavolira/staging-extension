/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.management.content.operations.site.contents;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

import org.exoplatform.management.content.operations.site.SiteUtil;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;
import org.gatein.management.api.operation.model.ExportTask;

import com.thoughtworks.xstream.XStream;

/**
 * @author <a href="mailto:bkhanfir@exoplatform.com">Boubaker Khanfir</a>
 * @version $Revision$
 */
public class SiteContentsActivitiesExportTask implements ExportTask {

  public static final String FILENAME = "/DocumentActivities.metadata";

  private final IdentityManager identityManager;
  private final List<ExoSocialActivity> activities;
  private final String siteName;

  public SiteContentsActivitiesExportTask(IdentityManager identityManager, List<ExoSocialActivity> activitiesList, String siteName) {
    this.activities = activitiesList;
    this.identityManager = identityManager;
    this.siteName = siteName;
  }

  @Override
  public String getEntry() {
    return getEntryPath(siteName);
  }

  public static String getEntryPath(String siteName) {
    return SiteUtil.getSiteContentsBasePath(siteName) + FILENAME;
  }

  @Override
  public void export(OutputStream outputStream) throws IOException {
    XStream xStream = new XStream();
    OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8");
    if (activities != null && activities.size() > 0) {
      for (ExoSocialActivity activity : activities) {
        Identity identity = identityManager.getIdentity(activity.getUserId(), true);
        if (identity != null) {
          String username = (String) identity.getProfile().getProperty(Profile.USERNAME);
          activity.setUserId(username);
        }

        identity = identityManager.getIdentity(activity.getPosterId(), true);
        if (identity != null) {
          String username = (String) identity.getProfile().getProperty(Profile.USERNAME);
          activity.setPosterId(username);
        }

        String[] commentedIds = activity.getCommentedIds();
        if (commentedIds != null && commentedIds.length > 0) {
          for (int i = 0; i < commentedIds.length; i++) {
            identity = identityManager.getIdentity(commentedIds[i], true);
            if (identity != null) {
              commentedIds[i] = (String) identity.getProfile().getProperty(Profile.USERNAME);
            }
          }
          activity.setCommentedIds(commentedIds);
        }
        String[] mentionedIds = activity.getMentionedIds();
        if (mentionedIds != null && mentionedIds.length > 0) {
          for (int i = 0; i < mentionedIds.length; i++) {
            identity = identityManager.getIdentity(mentionedIds[i], true);
            if (identity != null) {
              mentionedIds[i] = (String) identity.getProfile().getProperty(Profile.USERNAME);
            }
          }
          activity.setMentionedIds(mentionedIds);
        }
        String[] likeIdentityIds = activity.getLikeIdentityIds();
        if (likeIdentityIds != null && likeIdentityIds.length > 0) {
          for (int i = 0; i < likeIdentityIds.length; i++) {
            identity = identityManager.getIdentity(likeIdentityIds[i], true);
            if (identity != null) {
              likeIdentityIds[i] = (String) identity.getProfile().getProperty(Profile.USERNAME);
            }
          }
          activity.setLikeIdentityIds(likeIdentityIds);
        }
      }
    }
    xStream.toXML(activities, writer);
    writer.flush();
  }

}
