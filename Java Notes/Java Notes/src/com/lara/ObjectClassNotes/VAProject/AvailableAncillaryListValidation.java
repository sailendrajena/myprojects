package com.virginamerica.regression.emdphase2a.availableancillary;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.virginamerica.web3.BaseScenarioTest;
import com.virginamerica.web3.common.logging.ScenarioCommonUtil;
import com.virginamerica.web3.config.VX;
import com.virginamerica.web3.config.VX.VxAncillary;
import com.virginamerica.web3.config.VXMgr;



public class AvailableAncillaryListValidation extends BaseScenarioTest
{
	
	public static boolean getAncillaryListValidationStatus(String baseDir, String jsonPath) throws Exception
	{
		boolean status = false;
		
		ScenarioCommonUtil.INSTANCE.setScenarioBaseDir(baseDir);
		String responsePath = jsonPath + "response.json";
		String response = ScenarioCommonUtil
				.getControllerResponseFromFile(responsePath);
		JSONObject responseObj = new JSONObject(response);
		JSONArray anciListArr = responseObj.getJSONObject("response").getJSONObject("ancillaryDetails").getJSONArray("ancillaryList");
		
		String[] dummyList = null;//readAnciDummyList();
		
		List<String> responseAnciList = new ArrayList<String>();
		
		for (int i = 0; i < anciListArr.length(); i++)
		{
			responseAnciList.add(anciListArr.getJSONObject(i).getString("ancillaryId"));
		}
		
		System.out.println(responseAnciList);
		String[] responseList = (String[])responseAnciList.toArray(new String[responseAnciList.size()]);
		
		if (Arrays.equals(dummyList, responseList))
		{
			System.out.println("Same");
			status = true;
		}
		else
			System.out.println("Not Same");
		
			return status;
		
	}
	
	public static String[] readAnciDummyList()
	{
		VXMgr.INSTANCE.init();
		List<String> list = new ArrayList<String>();
		String[] anciDummyList = null;

		try
		{
			
			List<VxAncillary> vx = VXMgr.INSTANCE.getVx().getQueryAncillariesDetails().getVxSupportedAncillaries().getVxAncillary();
			for (int i = 0; i < vx.size(); i++)
			{
				list.add(vx.get(i).getSubCode().getSubCode());
			}
			System.out.println(list);
			anciDummyList = (String[])list.toArray(new String[list.size()]);
		}
		catch(Exception ex)
		{
			ex.printStackTrace();
		}
		
		
		return anciDummyList;
	}
}