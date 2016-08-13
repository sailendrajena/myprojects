package com.virginamerica.web3.common.logging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Singleton;
import com.virginamerica.web3.ads.model.AdsCardTypeNotFoundException;
import com.virginamerica.web3.common.util.FileSystemUtils;
import com.virginamerica.web3.common.util.JaxbUtil;
import com.virginamerica.web3.common.util.XMLUtils;
import com.virginamerica.web3.config.AppRecorder;
import com.virginamerica.web3.config.VXMgr;
import com.virginamerica.web3.config.AppRecorder.Gateways.Gateway;
import com.virginamerica.web3.config.AppRecorder.Gateways.Gateway.XMLElement;
import com.virginamerica.web3.config.VX.VxAncillary;
import com.virginamerica.web3.context.ApiFlowContext;
import com.virginamerica.web3.dao.DAOException;
import com.virginamerica.web3.domain.model.ErrorCode;
import com.virginamerica.web3.exception.VXEnvironmentException;
import com.virginamerica.web3.exception.VXException;
import com.virginamerica.web3.exception.VXRuntimeException;
import com.virginamerica.web3.gateway.BaseRESTGateway;
import com.virginamerica.web3.gateway.ll.NoAccountAvailableException;
import com.virginamerica.web3.gson.GsonUtil;

@Singleton
public enum ScenarioCommonUtil implements ScenarioCommon {
	INSTANCE;

	private static final Logger LOGGER = LoggerFactory
			.getLogger(ScenarioCommonUtil.class);

	private static String scenarioBaseDir;
	// * key = method/gateway name
	private Multimap<String, ObjRequestResponse> objectToCall;
	private Map<String, Integer> folderCounter;
	private Map<String, Integer> gatewayCounter;
	// * key=simpleName
	private Map<String, String> simpleNameToFullNameMap = new HashMap<String, String>();
	private final String CONFIG_FILE = "appRecorder.xml";
	private AppRecorder appRecorder;

	public void init() {
		cleanUp();

		JAXBContext ctx;
		try {
			ctx = JAXBContext.newInstance(AppRecorder.class);
		} catch (JAXBException e) {
			throw new RuntimeException("Failed to parse " + CONFIG_FILE + ": "
					+ e.getMessage(), e);
		}
		try {
			Unmarshaller u = ctx.createUnmarshaller();

			InputStream in = this.getClass().getClassLoader()
					.getResourceAsStream(CONFIG_FILE);
			Validate.notNull(in, CONFIG_FILE + " is not found");

			appRecorder = (AppRecorder) u.unmarshal(in);

			initGateways();

		} catch (JAXBException e) {
			throw new RuntimeException("Failed to parse " + CONFIG_FILE + ": "
					+ e.getMessage(), e);
		}
	}

	private void cleanUp() {
		appRecorder = null;
		simpleNameToFullNameMap = new HashMap<String, String>();
	}

	void initGateways() {
		List<Gateway> gatewayList = appRecorder.getGateways().getGatewayList();

		for (Gateway eachGateway : gatewayList) {
			// * init RQ
			{
				XMLElement xmlElement = eachGateway.getRequest();
				String key = getSimpleName(xmlElement.getClassName());
				LOGGER.debug(".initGateways(): adding " + key
						+ " to simpleNameToGatewayMap");

				simpleNameToFullNameMap.put(key, xmlElement.getClassName());
			}

			// * init RS
			{
				XMLElement xmlElement = eachGateway.getResponse();
				String key = getSimpleName(xmlElement.getClassName());
				LOGGER.debug(".initGateways(): adding " + key
						+ " to simpleNameToGatewayMap");

				simpleNameToFullNameMap.put(key, xmlElement.getClassName());
			}

		}
	}

	private String getResourcePath() {
		URL url;
		try {
			URL baseUrl = this.getClass().getResource(".");
			if (baseUrl != null) {
				url = new URL(baseUrl, ScenarioCommonUtil.scenarioBaseDir);
			} else {
				url = this.getClass().getResource(scenarioBaseDir);
			}
			if (baseUrl != null
					&& StringUtils.contains(baseUrl.getPath(), "test-classes")) {
				String resourcePath = StringUtils.substringBefore(baseUrl.getPath(), "/com");

				if (StringUtils.contains(resourcePath, "target/test-classes")) {
					resourcePath = StringUtils.replace(resourcePath, "target/test-classes", "test-resources");
				} else {
					throw new IllegalStateException("Unexpected resourcePath, should contain 'target/test-classes': " + resourcePath);
				}

				return resourcePath;
			} else {
				throw new ResourcePathException(
						ErrorCode.SF_RESOURCE_DIR_NOTFOUND,
						"getResourcePath() failed: " + url.getPath(), this);
			}
		} catch (MalformedURLException e) {
			throw new ResourcePathException(ErrorCode.SF_RESOURCE_DIR_NOTFOUND,
					"getResourcePath() failed:" + scenarioBaseDir, e, this);
		}
	}

	public String getScenarioDir(String name) {

		objectToCall = ArrayListMultimap.create();
		name = getGateWayName(name);
		if (StringUtils.isEmpty(name)) {
			return getResourcePath() + File.separator + scenarioBaseDir;
		} else {
			return getResourcePath() + File.separator + scenarioBaseDir
					+ File.separator + name;
		}
	}

	public void loadAll(String folderName, String dirPath,
			boolean hasMultipleRequest) {
		LOGGER.debug(".loadAll(): " + ", dirPath=" + dirPath);

		Collection<File> tranxDirList = FileSystemUtils
				.getListOfAllDirectories(dirPath);
		LOGGER.debug(".loadAll(): # of directories found "
				+ tranxDirList.size());

		for (File eachTranxDir : tranxDirList) {
			String tranxPath = eachTranxDir.getPath();

			if (StringUtils.equalsIgnoreCase(
					StringUtils.substringAfterLast(dirPath, File.separator),
					StringUtils.substringAfterLast(tranxPath, File.separator))) {
				// * omit timestamp folder
			} else {
				Collection<File> gwTransx = FileSystemUtils
						.getListOfAllFiles(tranxPath);

				File[] files = gwTransx.toArray(new File[gwTransx.size()]);
				File[] request = new File[gwTransx.size()];
				File response = null;
				for (int i = 0; i < gwTransx.size(); i++) {

					if (files[i].getName().contains("request")) {
						request[i] = files[i];
					} else {
						response = files[i];
					}
				}
				addFileFromLog(folderName, removeEmptyArrayValues(request),
						response);
			}
		}
	}

	private File[] removeEmptyArrayValues(File[] request) {
		List<File> s1 = new ArrayList<File>(Arrays.asList(request));
		s1.remove(null);
		File[] fileArray = s1.toArray(new File[s1.size()]);
		return fileArray;
	}

	public void loadAll(String folderName, String dirPath) {
		LOGGER.debug(".loadAll(): " + ", dirPath=" + dirPath);

		Collection<File> tranxDirList = FileSystemUtils
				.getListOfAllDirectories(dirPath);
		LOGGER.debug(".loadAll(): # of directories found "
				+ tranxDirList.size());

		for (File eachTranxDir : tranxDirList) {
			String tranxPath = eachTranxDir.getPath();

			if (!tranxPath.contains("#"))
				continue;

			if (StringUtils.equalsIgnoreCase(
					StringUtils.substringAfterLast(dirPath, File.separator),
					StringUtils.substringAfterLast(tranxPath, File.separator))) {
				// * omit timestamp folder
			} else {
				Collection<File> gwTransx = FileSystemUtils
						.getListOfAllFiles(tranxPath);
				// * should be 2 files - request.xml & response.xml
				Validate.isTrue(gwTransx.size() % 2 == 0, tranxPath
						+ " contains invalid file");
				File[] files = gwTransx.toArray(new File[2]);
				File request = files[0];
				File response = files[1];

				addFileFromLog(folderName, request, response);
			}
		}
	}

	private void addFileFromLog(String folderName, File[] requestFile,
			File responseFile) {
		String[] folderPath = null;
		String path = "";
		String[] requestBodies = new String[requestFile.length];
		String responseBody = "";
		for (int i = 0; i < requestFile.length; i++) {
			if (requestFile[i] != null)
				requestBodies[i] = fileToString(folderName, requestFile[i]);
		}

		if (responseFile != null)
			responseBody = fileToString(folderName, responseFile);

		if (requestFile.length > 0) {
			path = StringUtils.substringAfterLast(requestFile[0].getPath(),
					folderName);
		} else if (responseFile.isFile()) {
			path = StringUtils.substringAfterLast(responseFile.getPath(),
					folderName);
		}

		folderPath = path.split(path.contains("\\") ? "\\\\" : File.separator);

		ObjectRequestResponse objCall = new ObjectRequestResponse(folderName,
				requestBodies, responseBody, folderPath[1]);

		objectToCall.put(folderName, objCall);
		LOGGER.debug(".addFilesFromLog(): name=" + folderName + ", object="
				+ objCall);
	}

	private void addFileFromLog(String folderName, File requestFile,
			File responseFile) {
		LOGGER.debug(".addFilesFromLog(): adding trans for "
				+ requestFile.getPath() + ", " + responseFile.getPath());
		String[] folderPath = null;
		String path = "";
		folderName = getGateWayName(folderName);
		String requestBody = fileToString(folderName, requestFile);
		String responseBody = fileToString(folderName, responseFile);

		if (requestFile.isFile()) {
			path = StringUtils.substringAfterLast(requestFile.getPath(),
					folderName);
		} else if (responseFile.isFile()) {
			path = StringUtils.substringAfterLast(responseFile.getPath(),
					folderName);
		}

		folderPath = path.split(path.contains("\\") ? "\\\\" : File.separator);
		ObjectRequestResponse objCall = new ObjectRequestResponse(folderName,
				requestBody, responseBody, folderPath[1]);
		objectToCall.put(folderName, objCall);
		LOGGER.debug(".addFilesFromLog(): name=" + folderName + ", object="
				+ objCall);

	}

	String fileToString(String folderName, File xmlFile) {
		try {
			String body = FileUtils.readFileToString(xmlFile);
			LOGGER.debug(".toObject(): body=" + body);

			return body;
		} catch (IOException e1) {
			LOGGER.error(".toObject(): ", e1);
			throw new RuntimeException(".toObject() failed to get body: "
					+ folderName + ", " + xmlFile.getPath(), e1);
		}
	}

	public <T> Object getMatchingResponse(String gatewayName, Object[] request,
			Class<T> response) throws Exception {
		if(void.class.equals(response)) {
			return response;
		}
		String requestBody = "";

		LOGGER.debug(".getMatchingResponse(): " + ", gatewayName="
				+ gatewayName);

		Collection<ObjRequestResponse> objCalls = getObjectToCall().get(
				gatewayName);
		for (ObjRequestResponse eachObjCall : objCalls) {
			int counter = folderCounter.get(gatewayName) != null ? folderCounter
					.get(gatewayName) : 1;
			if (request.length > 0) {
				for (Object req : request) {
					requestBody = (String) JaxbUtil.objToString(req,
							req.getClass());
					for (String reqBody : eachObjCall.getReqBody()) {
						try {

							if (XMLUtils.isXMLEquals(requestBody, reqBody,
									new ArrayList<String>(),
									getGatewayStubMapping())) {
								break;
							}

						} catch (SAXException | IOException e) {
							LOGGER.error(".log(): failed to compare xmls for "
									+ gatewayName + ": " + e.getMessage(), e);
						}

					}
				}
			}
			String responseBody = "";
			Object bodyObj = null;
			String[] index = eachObjCall.getFolderIndex().split("#");

			if (Integer.parseInt(index[1]) == counter) {
				folderCounter.put(gatewayName, counter + 1);

				if (StringUtils.isNotBlank(eachObjCall
						.getResponseBody())) {
					responseBody = eachObjCall.getResponseBody();
					if (responseBody.contains("Exception")) {
						throwException(responseBody);
					}
					bodyObj = JaxbUtil
							.xmlStringToObject(responseBody, response, gatewayName);
					LOGGER.debug(".getMatchingResponse(): " + ", gatewayName="
							+ gatewayName + ", matching response found :-)");
				}
				return bodyObj;
			}
		}
		throw new ResponseNotFoundException(ErrorCode.SF_RESPONSE_NOTFOUND,
				"No matching response found", this, requestBody);
	}

	private void throwException(String responseBody) throws Exception {
		if (responseBody.contains("DAOException")) {
			throw new DAOException(ErrorCode.ERROR_CODE_TEST,
					"has exception in response file", ErrorCode.ERROR_CODE_TEST);
		} else if (responseBody.contains("VXRuntimeException")) {
			throw new VXRuntimeException(ErrorCode.ERROR_CODE_TEST,
					"has exception in response file", ErrorCode.ERROR_CODE_TEST);
		} else if (responseBody.contains("VXEnvironmentException")) {
			throw new VXEnvironmentException(ErrorCode.ERROR_CODE_TEST,
					"has exception in response file", "");
		} else if (responseBody.contains("VXException")) {
			throw new VXException(ErrorCode.ERROR_CODE_TEST,
					"has exception in response file", ErrorCode.ERROR_CODE_TEST);
		} else if (responseBody.contains("AdsCardTypeNotFoundException")) {
			throw new AdsCardTypeNotFoundException(ErrorCode.ADS_CARD_TYPE_NOT_FOUND,
					"ADS card type not found for ffpNum : ",ErrorCode.ADS_CARD_TYPE_NOT_FOUND);
		} else if (responseBody.contains("Exception")) {
			throw new Exception("has exception in response file, Based on your log file add "
					+ "appropriate exception in ScenarioCommonUtil.throwException(). method");
		}
	}

	/**
	 * @param gatewayName
	 * @param request
	 * @return Jaxb obj
	 * @throws ResponseNotFoundException
	 */
	public Object getMatchingResponse(String gatewayName, Object request)
			throws ResponseNotFoundException {
		LOGGER.debug(".getMatchingResponse(): " + ", gatewayName="
				+ gatewayName + ", request=" + request);

		String requestBody = (String) JaxbUtil.objToString(request,
				request.getClass());

		requestBody = gatewayName.equalsIgnoreCase("IRopGateway") ? getSubSetXmlForRequestIrop(requestBody)
				: requestBody;

		Collection<ObjRequestResponse> wsCalls = getObjectToCall().get(
				gatewayName);

		List<String> ignoreXMLElement = getIgnoreXMLElement(gatewayName);

		for (ObjRequestResponse eachWebServiceCall : wsCalls) {
			String wsCallBody = eachWebServiceCall.getRequestBody();

			if (gatewayName.equalsIgnoreCase("IRopGateway")) {
				wsCallBody = getSubSetXMLFromFileIrop(wsCallBody);
				requestBody = wrapToRootElement(requestBody);
			}

			int counter = gatewayCounter.get(gatewayName) != null ? gatewayCounter
					.get(gatewayName) : 1;
			try {
				if (XMLUtils.isXMLEquals(requestBody, wsCallBody,
						ignoreXMLElement, getGatewayStubMapping())) {
					String[] index = eachWebServiceCall.getFolderIndex().split(
							"#");

					if (Integer.parseInt(index[1].split("_")[0]) == counter) {
						gatewayCounter.put(gatewayName, counter + 1);
						return getResponseObject(gatewayName, request, eachWebServiceCall);
					}
					
					else if(StringUtils.equalsIgnoreCase(gatewayName, "SSSAdvShopGateway")){
						return getResponseObject(gatewayName, request, eachWebServiceCall);
					}
				}
			} catch (SAXException | IOException e) {
				LOGGER.error(".log(): failed to compare xmls for "
						+ gatewayName + ": " + e.getMessage(), e);
			}
		}
		throw new ResponseNotFoundException(ErrorCode.SF_RESPONSE_NOTFOUND,
				"No matching response found: gatewayName=" + gatewayName
						+ ", requestBody=" + requestBody, this, requestBody);
	}
	
	
	private Object getResponseObject(String gatewayName, Object request, ObjRequestResponse eachWebServiceCall){
		String responseBody = eachWebServiceCall
				.getResponseBody();
		if (StringUtils.isBlank(responseBody)) {
			throw new ResponseNotFoundException(ErrorCode.SF_RESPONSE_NOTFOUND,
					"No response file found.", gatewayName,
					eachWebServiceCall.getRequestBody());
		}
		if (responseBody.contains("soap:Fault")
				&& ApiFlowContext
				.getCurrentContext()
				.getAuthToken()
				.equals("11f76be1-5e31-4f04-8057-544dd5b80fbf")) {

			throw new NoAccountAvailableException(
					ErrorCode.GW_NO_ACCOUNT_AVAILABLE,
					"No LL account for:" + request, this);

		}
		String simpleName = XMLUtils
				.getSimpleClassName(responseBody);
		Class<?> clazz;
		try {
			clazz = getClassBySimpleName(simpleName);
		} catch (ClassNotFoundException e) {
			throw new ResponseNotFoundException(
					ErrorCode.SF_RESPONSE_NOTFOUND,
					"No matching response found for gatewayName= " + gatewayName + ", className='" + simpleName +
                            "', responseBody=" + responseBody + ": " + e.getMessage(), this, responseBody);

		}

		Object bodyObj = JaxbUtil.xmlStringToObject(
				responseBody, clazz, gatewayName);

		LOGGER.debug(".getMatchingResponse(): "
				+ ", gatewayName=" + gatewayName
				+ ", matching response found :-)");

		System.out.println(".getMatchingResponse(): "
				+ ", gatewayName=" + gatewayName
				+ ", matching response found :-)");

		return bodyObj;
	}
	
	@SuppressWarnings("unchecked")
	public Object getMatchingRestResponse(@SuppressWarnings("rawtypes") BaseRESTGateway gateway,
			String endpoint, HttpEntity<?> entity) {

		String gatewayName = gateway.getGatewayName();
		String requestBody = "";
		try {
			requestBody = BaseRESTGateway.buildRestRequestToLog(endpoint,
					entity);
		} catch (JsonProcessingException e) {
			LOGGER.error(".log(): failed to build request json " + gatewayName
					+ ": " + e.getMessage(), e);
		}

		// Split header from body
		String[] request = StringUtils.splitByWholeSeparator(requestBody, "}{");

		Collection<ObjRequestResponse> wsCalls = getObjectToCall().get(
				gatewayName);
		for (ObjRequestResponse eachWebServiceCall : wsCalls) {
			String wsCallBody = eachWebServiceCall.getRequestBody();
			// Split ws request's header from body
			String[] wsRequest = StringUtils.splitByWholeSeparator(wsCallBody,
					"}{");
			int counter = gatewayCounter.get(gatewayName) != null ? gatewayCounter
					.get(gatewayName) : 1;
			try {
				if (wsRequest.length != request.length) {
					continue;
				}
				// Compare header leniently
				JSONCompareResult headerResult = JSONCompare.compareJSON(
						request[0] + "}", wsRequest[0] + "}",
						JSONCompareMode.LENIENT);
				boolean passed = headerResult.passed();

				if (passed && request.length == 2) {
					// If header matches, compare body. Comparison is extensible
					// (All fields should match irrespective of the order)
					JSONCompareResult bodyResult = JSONCompare.compareJSON("{"
							+ request[1], "{" + wsRequest[1],
							JSONCompareMode.NON_EXTENSIBLE);
					passed = bodyResult.passed();
				}
				if (passed) {

					String[] index = eachWebServiceCall.getFolderIndex().split(
							"#");
					if (Integer.parseInt(index[1].split("_")[0]) == counter) {
						gatewayCounter.put(gatewayName, counter + 1);
						String responseBody = eachWebServiceCall
								.getResponseBody();
						Object response = BaseRESTGateway.restMapper.readValue(
								responseBody, gateway.getResponseType());

						LOGGER.debug(".getMatchingResponse(): "
								+ ", gatewayName=" + gatewayName
								+ ", matching response found :-)");

						System.out.println(".getMatchingResponse(): "
								+ ", gatewayName=" + gatewayName
								+ ", matching response found :-)");
						return response;
					}
				}

			} catch (JSONException | IOException e) {
				LOGGER.error(".log(): failed to compare jsons for "
						+ gatewayName + ": " + e.getMessage(), e);
			}
		}
		throw new ResponseNotFoundException(ErrorCode.SF_RESPONSE_NOTFOUND,
				"No matching response found: gatewayName=" + gatewayName
						+ ", requestBody=" + requestBody, this, requestBody);

	}

	private String getSubSetXmlForRequestIrop(String requestBody) {
		String[] xmlDeclaration = requestBody.split("<IROPRQ");
		String[] irop = requestBody.split("</ns2:wsChannel>");
		String[] rop = irop[1].split("</IROPRQ>");
		requestBody = xmlDeclaration[0] + rop[0];

		return requestBody.replace("ns2:", "");
	}

	private String getSubSetXMLFromFileIrop(String wsCallBody) {
		String[] irop = wsCallBody.split("ns4:wsChannel>");
		String[] rop = irop[2].split("</ns4:ReqObj>");
		wsCallBody = wrapToRootElement(rop[0]);

		return wsCallBody.replace("ns4:", "");// .concat(">");
	}

	private String wrapToRootElement(String requestBody) {
		boolean hasMultiFlightDetails = StringUtils.countMatches(requestBody,
				"<IROPFlightDetails>") > 1;

		String wrappedStringBody = requestBody;
		if (hasMultiFlightDetails && requestBody.contains("<?xml version")
				&& !StringUtils.contains(requestBody, "<Root_Element>")) {
			wrappedStringBody = requestBody.replace("?>",
					"?>".concat("<Root_Element>"));
			wrappedStringBody = wrappedStringBody.concat("</Root_Element>");
		}

		if (StringUtils.countMatches(requestBody, "<ns4:IROPFlightDetails>") > 1) {
			wrappedStringBody = "<Root_Element>".concat(wrappedStringBody);
			wrappedStringBody = wrappedStringBody.concat("</Root_Element>");

		}
		return wrappedStringBody;
	}

	public static String getControllerResponseFromFile(String filePath)
			throws IOException {
		File responsefile = new File(filePath);
		InputStream inStream = new FileInputStream(responsefile);
		StringWriter sw = new StringWriter();
		IOUtils.copy(inStream, sw, Charset.forName("UTF-8"));
		return sw.toString();
	}

	public static Reader getControllerRequestFromFile(String filePath)
			throws FileNotFoundException {
		File requestfile = new File(filePath);
		InputStream inputStream = new FileInputStream(requestfile);
		Reader rd = new InputStreamReader(inputStream, Charset.forName("UTF-8"));
		return rd;
	}

	public static Gson getGsonPrinter() {
		GsonBuilder builder = GsonUtil.getGsonBuilder(new BigDecimal("1.0"));
		builder.setPrettyPrinting();
		Gson gson = builder.create();
		return gson;
	}

	public Class<?> getClassBySimpleName(String simpleName)
			throws ClassNotFoundException {
		LOGGER.debug(".getClassBySimpleName(): simpleName=" + simpleName);

		String fullClassName = getFullname (simpleName);
		LOGGER.debug(".getClassBySimpleName(): fullClassName=" + fullClassName);

		if (StringUtils.isNotBlank(fullClassName)) {
			return Class.forName(fullClassName);
		}

		return Class.forName("");

	}

	String getFullname(String simpleName) {
		String matchClassName = matchClassName(simpleName);
		String fullName = this.simpleNameToFullNameMap.get(matchClassName);

		if (StringUtils.isBlank(fullName))
			LOGGER.warn("name not found in apprecorder.xml: " + matchClassName);

		return fullName;

	}

	String getSimpleName(String fullName) {
		return StringUtils.substringAfterLast(fullName, ".");
	}

	public Map<String, String> getGatewayStubMapping() {
		Map<String, String> gatewayStubMapping = new HashMap<String, String>();
		gatewayStubMapping.put("ACSFlightSeatMapRQACS", "ACSFlightSeatMapRQ");
		gatewayStubMapping.put("ACSPassengerListRQACS", "ACSPassengerListRQ");
		gatewayStubMapping.put("ACSPassengerDataRQACS", "ACSPassengerDataRQ");
		gatewayStubMapping.put("ACSCheckInPassengerRQACS",
				"ACSCheckInPassengerRQ");
		gatewayStubMapping.put("ACSAddToPriorityListRQACS",
				"ACSAddToPriorityListRQ");
		gatewayStubMapping.put("ACSFlightDetailRQACS", "ACSFlightDetailRQ");
		gatewayStubMapping.put("ACSSeatChangeRQACS", "ACSSeatChangeRQ");
		gatewayStubMapping.put("ACSIssueBagTagRQACS", "ACSIssueBagTagRQ");
		gatewayStubMapping.put("ELEAttachProfileToPNRRQ", "ELE_AttachProfileToPNRRQ");
		gatewayStubMapping.put("MadeOffer41Response", "MadeOffer41Response");
		gatewayStubMapping.put("PrescreenAcceptance47Response", "PrescreenAcceptance47Response");
		gatewayStubMapping.put("OTA_AirLowFareSearchRQ", "OTA_AirLowFareSearchRQ");
		gatewayStubMapping.put("OTAAirLowFareSearchRQ", "OTAAirLowFareSearchRQ");
		return gatewayStubMapping;
	}

	/**
	 * Retrieve matched class name
	 *
	 * @param className
	 * @return
	 */
	public String matchClassName(String className) {
		String simpleName;
		switch (className) {
			case "ACSCheckInPassengerRQ":
				simpleName = "ACSCheckInPassengerRQACS";
				break;
			case "ACSCheckInPassengerRS":
				simpleName = "ACSCheckInPassengerRSACS";
				break;
			case "ACSPassengerDataRQ":
				simpleName = "ACSPassengerDataRQACS";
				break;
			case "ACSPassengerDataRS":
				simpleName = "ACSPassengerDataRSACS";
				break;
			case "ACSSeatChangeRQ":
				simpleName = "ACSSeatChangeRQACS";
				break;
			case "ACSSeatChangeRS":
				simpleName = "ACSSeatChangeRSACS";
				break;
			case "createBarcodeURLList":
				simpleName = "CreateBarcodeURLList";
				break;
			case "createBarcodeURLListResponse":
				simpleName = "CreateBarcodeURLListResponse";
				break;
			case "ACSAddToPriorityListRQ":
				simpleName = "ACSAddToPriorityListRQACS";
				break;
			case "ACSAddToPriorityListRS":
				simpleName = "ACSAddToPriorityListRSACS";
				break;
			case "emSendEmailRspResponse":
				simpleName = "EmSendEmailRspResponse";
				break;
			case "ACSFlightSeatMapRQ":
				simpleName = "ACSFlightSeatMapRQACS";
				break;
			case "ACSFlightSeatMapRS":
				simpleName = "ACSFlightSeatMapRSACS";
				break;
			case "ACSPassengerListRQ":
				simpleName = "ACSPassengerListRQACS";
				break;
			case "ACSPassengerListRS":
				simpleName = "ACSPassengerListRSACS";
				break;
			case "getEligibleIROPFiltered":
				simpleName = "IROPRQ";
				break;
			case "getEligibleIROPFilteredResponse":
				simpleName = "GetEligibleIROPFilteredResponse";
				break;
			case "ACSFlightDetailRQ":
				simpleName = "ACSFlightDetailRQACS";
				break;
			case "ACSFlightDetailRS":
				simpleName = "ACSFlightDetailRSACS";
				break;
			case "GetPointPricesResponse":
				simpleName = "ArrayOfProductToPrice";
				break;
			case "GetShopperByEmailResponse":
				simpleName = "Shopper";
				break;
			case "InstantCreditRequest44":
				simpleName = "InstantCreditRequest44";
				break;
			case "instantCredit44Response":
				simpleName = "InstantCredit44Response";
				break;
			case "AuthenticateUserResponse":
				simpleName = "AuthenticationResult";
				break;
			case "ACSIssueBagTagRQ":
				simpleName = "ACSIssueBagTagRQACS";
				break;
			case "ACSIssueBagTagRS":
				simpleName = "ACSIssueBagTagRSACS";
				break;
			case "madeOffer41Response":
				simpleName = "MadeOffer41Response";
				break;
			case "prescreenAcceptance47Response":
				simpleName = "PrescreenAcceptance47Response";
				break;
			case "olps42Response":
				simpleName = "Olps42Response";
				break;
			default:
				simpleName = className;
		}
		return simpleName;
	}

	public List<String> getIgnoreXMLElement(String gatewayName) {
		List<String> ignoreXMLElements = new ArrayList<String>();
		if (gatewayName.equals("TravelBankPaymentGateway")) {
			ignoreXMLElements.add("SystemDateTime");
			ignoreXMLElements.add("LocalDateTime");
		} else if (gatewayName.equals("ShopperRedemptionsByLLIdGateway")) {
			ignoreXMLElements.add("toDate");
			ignoreXMLElements.add("fromDate");
		} else if (gatewayName.equals("CollectEMDFeeGateway")){
			ignoreXMLElements.add("couponNumber");
		}
		return ignoreXMLElements;
	}

	public String getGateWayName(String gateWayName) {
		if (gateWayName.contains("$$")) {
			gateWayName = gateWayName.replace("$$", "#").split("#")[0];
		}
		return gateWayName;
	}

	public Map<String, Integer> getGatewayCounter() {
		return gatewayCounter;
	}

	public void setGatewayCounter(Map<String, Integer> gatewayCounter) {
		this.gatewayCounter = gatewayCounter;
	}

	public String getScenarioBaseDir() {
		return getStaticScenarioBaseDir();
	}

	public static String getStaticScenarioBaseDir() {
		return ScenarioCommonUtil.scenarioBaseDir;
	}

	// SHOULD ONLY BE USED FOR SCENARIO TESTCASES
	public void setScenarioBaseDir(String scenarioBaseDir) {
		setStaticScenarioBaseDir(scenarioBaseDir);
	}

	public static void setStaticScenarioBaseDir(String scenarioBaseDir) {
		ScenarioCommonUtil.scenarioBaseDir = scenarioBaseDir;
	}

	public Multimap<String, ObjRequestResponse> getObjectToCall() {
		return objectToCall;
	}

	public void setObjectToCall(
			Multimap<String, ObjRequestResponse> objectToCall) {
		this.objectToCall = objectToCall;
	}

	public Map<String, Integer> getFolderCounter() {
		return folderCounter;
	}

	public void setFolderCounter(Map<String, Integer> folderCounter) {
		this.folderCounter = folderCounter;
	}
	
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
	           
	           String[] dummyList = readAnciDummyList();
	           
	           List<String> responseAnciList = new ArrayList<String>();
	           
	           for (int i = 0; i < anciListArr.length(); i++)
	           {
	               responseAnciList.add(anciListArr.getJSONObject(i).getString("ancillaryId"));
	           }
	           
	           String[] responseList = (String[])responseAnciList.toArray(new String[responseAnciList.size()]);
	           
	           if (Arrays.equals(dummyList, responseList))
	           {
	               status = true;
	           }
	       }
	       catch(Exception ex)
	       {
	           ex.printStackTrace();
	       }
	       
	       return status;
	   }
	
	//Added By Sailendra Narayan Jena(VA Offshore Team) on 1st of Sept, 2015.
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
			anciDummyList = (String[])list.toArray(new String[list.size()]);
		}
		catch(Exception ex)
		{
			ex.printStackTrace();
		}
		
		
		return anciDummyList;
	}
}
