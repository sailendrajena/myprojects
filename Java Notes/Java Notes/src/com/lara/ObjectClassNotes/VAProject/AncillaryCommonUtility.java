package com.virginamerica.regression.emdphase2a.availableancillary;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.virginamerica.web3.common.logging.ScenarioCommonUtil;
import com.virginamerica.web3.config.VX.VxAncillary;
import com.virginamerica.web3.config.VXMgr;

public class AncillaryCommonUtility
{
	private static String baseDir = File.separator + "regression" + File.separator
			+ "emdphase2a" + File.separator + "QueryAncillary" + File.separator
			+ "OWDir" + File.separator
			+ "AncillariesController.getAvailableAncillaries" + File.separator;
	private static String jsonPath = "test-resources" + File.separator + "regression"
			+ File.separator + "emdphase2a" + File.separator + "QueryAncillary"
			+ File.separator + "OWDir" + File.separator
			+ "AncillariesController.getAvailableAncillaries" + File.separator;
	
	//Added By Sailendra Narayan Jena(VA Offshore Team) on 1st of Sept, 2015.
		public static boolean getAncillaryListValidation(String baseDir, String jsonPath)
		   {
		       boolean status = false;
		       try
		       {
		           ScenarioCommonUtil.INSTANCE.setScenarioBaseDir(baseDir);
		           String responsePath = jsonPath + "response.json";
		           String response = ScenarioCommonUtil
		                   .getControllerResponseFromFile(responsePath);
		           JSONObject responseObj = new JSONObject(response);
		           JSONArray anciListArr = responseObj.getJSONObject("response").getJSONObject("ancillaryDetails").getJSONArray("ancillaryList");
		           
		           List<String> dummyList = getVxAnciList();
		           
		           List<String> resAnciList = new ArrayList<String>();
		           
		           for (int i = 0; i < anciListArr.length(); i++)
		           {
		        	   resAnciList.add(anciListArr.getJSONObject(i).getString("ancillaryId"));
		           }
		           
		           if (resAnciList.size() == dummyList.size())
		           {
		        	   throw new Exception("Ancillary List size is not matching");
		           }
		           else
		           {
		        	   for (int i = 0; i < resAnciList.size(); i++)
		        	   {
		        		  for (int j = 0; j < dummyList.size(); j++)
		        		  {
							if (resAnciList.equals(dummyList))
							{
								System.out.println("same");
								status = true;
							}
							else
							{
								System.out.println("Not Same");
								status = false;
							}
		        		  }
		        	   }
		           }
		           
		       }
		       catch(Exception ex)
		       {
		           ex.printStackTrace();
		       }
		       
		       return status;
		   }
		
		//Added By Sailendra Narayan Jena(VA Offshore Team) on 1st of Sept, 2015.
		public static List<String> getVxAnciList()
		{
			VXMgr.INSTANCE.init();
			List<String> vxAnciList = new ArrayList<String>();

			try
			{
				List<VxAncillary> vx = VXMgr.INSTANCE.getVx().getQueryAncillariesDetails().getVxSupportedAncillaries().getVxAncillary();
				
				for (int i = 0; i < vx.size(); i++)
				{
					vxAnciList.add(vx.get(i).getSubCode().getSubCode());
				}
				System.out.println(vxAnciList);
				
			}
			catch(Exception ex)
			{
				ex.printStackTrace();
			}
			
			
			return vxAnciList;
		}
		
		public static void main(String[] args)
		{
			
			boolean status = AncillaryCommonUtility.getAncillaryListValidation(baseDir, jsonPath);
		}

}
