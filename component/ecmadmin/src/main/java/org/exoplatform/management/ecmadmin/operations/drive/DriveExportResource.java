package org.exoplatform.management.ecmadmin.operations.drive;

import org.exoplatform.container.xml.*;
import org.exoplatform.services.cms.drives.DriveData;
import org.exoplatform.services.cms.drives.ManageDriveService;
import org.exoplatform.services.cms.drives.impl.ManageDrivePlugin;
import org.gatein.management.api.exceptions.OperationException;
import org.gatein.management.api.operation.*;
import org.gatein.management.api.operation.model.ExportResourceModel;

import java.util.ArrayList;
import java.util.List;

/**
 * @author <a href="mailto:bkhanfir@exoplatform.com">Boubaker Khanfir</a>
 * @version $Revision$
 */
public class DriveExportResource implements OperationHandler {

  private static final String EXPORT_BASE_PATH = "ecmadmin/drive";

  private ManageDriveService driveService;

  @Override
  public void execute(OperationContext operationContext, ResultHandler resultHandler) throws OperationException {
    OperationAttributes attributes = operationContext.getAttributes();
    List<String> filters = attributes.getValues("filter");
    if (driveService == null) {
      driveService = operationContext.getRuntimeContext().getRuntimeComponent(ManageDriveService.class);
    }
    Configuration configuration = null;
    try {
      ExternalComponentPlugins externalComponentPlugins = new ExternalComponentPlugins();
      externalComponentPlugins.setTargetComponent(ManageDriveService.class.getName());
      ArrayList<ComponentPlugin> componentPluginsList = new ArrayList<ComponentPlugin>();
      externalComponentPlugins.setComponentPlugins(componentPluginsList);

      ComponentPlugin templatesComponentPlugin = new ComponentPlugin();
      templatesComponentPlugin.setName("manage.drive.plugin");
      templatesComponentPlugin.setSetMethod("setManageDrivePlugin");
      templatesComponentPlugin.setType(ManageDrivePlugin.class.getName());

      InitParams templatesPluginInitParams = new InitParams();
      templatesComponentPlugin.setInitParams(templatesPluginInitParams);
      componentPluginsList.add(templatesComponentPlugin);

      List<DriveData> driveDataList = driveService.getAllDrives(true);
      for (DriveData driveData : driveDataList) {
        if (filters.isEmpty() || filters.contains(driveData.getName())) {
          ObjectParameter objectParam = new ObjectParameter();
          objectParam.setName(driveData.getName());
          objectParam.setObject(driveData);
          templatesPluginInitParams.addParam(objectParam);
        }
      }

      configuration = new Configuration();
      configuration.addExternalComponentPlugins(externalComponentPlugins);

    } catch (Exception exception) {
      throw new OperationException(OperationNames.EXPORT_RESOURCE, "Error while retrieving drives: ", exception);
    }
    resultHandler.completed(new ExportResourceModel(new DriveExportTask(configuration, EXPORT_BASE_PATH)));
  }

}