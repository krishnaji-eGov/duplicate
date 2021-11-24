package org.egov.waterconnection.service;

import static org.egov.waterconnection.constants.WCConstants.APPROVE_CONNECTION;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.egov.common.contract.request.RequestInfo;
import org.egov.tracer.model.CustomException;
import org.egov.waterconnection.config.WSConfiguration;
import org.egov.waterconnection.constants.WCConstants;
import org.egov.waterconnection.producer.WaterConnectionProducer;
import org.egov.waterconnection.repository.ElasticSearchRepository;
import org.egov.waterconnection.repository.WaterDao;
import org.egov.waterconnection.repository.WaterDaoImpl;
import org.egov.waterconnection.repository.WaterRepository;
import org.egov.waterconnection.util.WaterServicesUtil;
import org.egov.waterconnection.validator.ActionValidator;
import org.egov.waterconnection.validator.MDMSValidator;
import org.egov.waterconnection.validator.ValidateProperty;
import org.egov.waterconnection.validator.WaterConnectionValidator;
import org.egov.waterconnection.web.models.AuditDetails;
import org.egov.waterconnection.web.models.BillingCycle;
import org.egov.waterconnection.web.models.CheckList;
import org.egov.waterconnection.web.models.Feedback;
import org.egov.waterconnection.web.models.FeedbackRequest;
import org.egov.waterconnection.web.models.FeedbackSearchCriteria;
import org.egov.waterconnection.web.models.LastMonthSummary;
import org.egov.waterconnection.web.models.Property;
import org.egov.waterconnection.web.models.RevenueDashboard;
import org.egov.waterconnection.web.models.SearchCriteria;
import org.egov.waterconnection.web.models.WaterConnection;
import org.egov.waterconnection.web.models.WaterConnectionRequest;
import org.egov.waterconnection.web.models.WaterConnectionResponse;
import org.egov.waterconnection.web.models.workflow.BusinessService;
import org.egov.waterconnection.workflow.WorkflowIntegrator;
import org.egov.waterconnection.workflow.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;

@Component
public class WaterServiceImpl implements WaterService {

	@Autowired
	private WaterDao waterDao;

	@Autowired
	private WaterConnectionValidator waterConnectionValidator;

	@Autowired
	private ValidateProperty validateProperty;

	@Autowired
	private MDMSValidator mDMSValidator;

	@Autowired
	private EnrichmentService enrichmentService;

	@Autowired
	private WorkflowIntegrator wfIntegrator;

	@Autowired
	private WSConfiguration config;

	@Autowired
	private WorkflowService workflowService;

	@Autowired
	private ActionValidator actionValidator;

	@Autowired
	private WaterServicesUtil waterServiceUtil;

	@Autowired
	private CalculationService calculationService;

	@Autowired
	private WaterDaoImpl waterDaoImpl;

	@Autowired
	private UserService userService;

	@Autowired
	private WaterServicesUtil wsUtil;

	@Autowired
	private WaterConnectionProducer waterConnectionProducer;

	@Autowired
	private WaterRepository repository;
	
	@Autowired
	private ElasticSearchRepository elasticSearchRepository;

	/**
	 * 
	 * @param waterConnectionRequest WaterConnectionRequest contains water
	 *                               connection to be created
	 * @return List of WaterConnection after create
	 */
	@Override
	public List<WaterConnection> createWaterConnection(WaterConnectionRequest waterConnectionRequest) {
		int reqType = WCConstants.CREATE_APPLICATION;
		if (wsUtil.isModifyConnectionRequest(waterConnectionRequest)) {
			List<WaterConnection> previousConnectionsList = getAllWaterApplications(waterConnectionRequest);

			// Validate any process Instance exists with WF
//			if (!CollectionUtils.isEmpty(previousConnectionsList)) {
//				workflowService.validateInProgressWF(previousConnectionsList, waterConnectionRequest.getRequestInfo(),
//						waterConnectionRequest.getWaterConnection().getTenantId());
//			}
			reqType = WCConstants.MODIFY_CONNECTION;
		}
		mDMSValidator.validateMISFields(waterConnectionRequest);
		waterConnectionValidator.validateWaterConnection(waterConnectionRequest, reqType);
		Property property = validateProperty.getOrValidateProperty(waterConnectionRequest);
		validateProperty.validatePropertyFields(property, waterConnectionRequest.getRequestInfo());
		mDMSValidator.validateMasterForCreateRequest(waterConnectionRequest);
		enrichmentService.enrichWaterConnection(waterConnectionRequest, reqType);
		System.out.println("creating user");
		userService.createUser(waterConnectionRequest);
		System.out.println("created user   " + config.getIsExternalWorkFlowEnabled());
		// call work-flow
		if (config.getIsExternalWorkFlowEnabled() != null && config.getIsExternalWorkFlowEnabled())
			wfIntegrator.callWorkFlow(waterConnectionRequest, property);
		System.out.println("calling save user   ");

		enrichmentService.postStatusEnrichment(waterConnectionRequest);
		waterDao.saveWaterConnection(waterConnectionRequest);
		if (waterConnectionRequest.getWaterConnection().getArrears() != null
				&& waterConnectionRequest.getWaterConnection().getArrears().intValue() > 0) {
			calculationService.calculateFeeAndGenerateDemand(waterConnectionRequest, property);
		}

		return Arrays.asList(waterConnectionRequest.getWaterConnection());
	}

	/**
	 * 
	 * @param criteria    WaterConnectionSearchCriteria contains search criteria on
	 *                    water connection
	 * @param requestInfo
	 * @return List of matching water connection
	 */
	public WaterConnectionResponse search(SearchCriteria criteria, RequestInfo requestInfo) {
		WaterConnectionResponse waterConnection = getWaterConnectionsList(criteria, requestInfo);
		if (!StringUtils.isEmpty(criteria.getSearchType())
				&& criteria.getSearchType().equals(WCConstants.SEARCH_TYPE_CONNECTION)) {
			waterConnection
					.setWaterConnection(enrichmentService.filterConnections(waterConnection.getWaterConnection()));
			if (criteria.getIsPropertyDetailsRequired()) {
				waterConnection.setWaterConnection(enrichmentService
						.enrichPropertyDetails(waterConnection.getWaterConnection(), criteria, requestInfo));

			}
		}
		waterConnectionValidator.validatePropertyForConnection(waterConnection.getWaterConnection());
		enrichmentService.enrichConnectionHolderDeatils(waterConnection.getWaterConnection(), criteria, requestInfo);
		return waterConnection;
	}

	/**
	 * 
	 * @param criteria    WaterConnectionSearchCriteria contains search criteria on
	 *                    water connection
	 * @param requestInfo
	 * @return List of matching water connection
	 */
	public WaterConnectionResponse getWaterConnectionsList(SearchCriteria criteria, RequestInfo requestInfo) {
		return waterDao.getWaterConnectionList(criteria, requestInfo);
	}

	/**
	 * 
	 * @param waterConnectionRequest WaterConnectionRequest contains water
	 *                               connection to be updated
	 * @return List of WaterConnection after update
	 */
	@Override
	public List<WaterConnection> updateWaterConnection(WaterConnectionRequest waterConnectionRequest) {

		if (wsUtil.isModifyConnectionRequest(waterConnectionRequest)) {
			// Received request to update the connection for modifyConnection WF
			return updateWaterConnectionForModifyFlow(waterConnectionRequest);
		}
		mDMSValidator.validateMISFields(waterConnectionRequest);
		waterConnectionValidator.validateWaterConnection(waterConnectionRequest, WCConstants.UPDATE_APPLICATION);
		mDMSValidator.validateMasterData(waterConnectionRequest, WCConstants.UPDATE_APPLICATION);
		Property property = validateProperty.getOrValidateProperty(waterConnectionRequest);
		validateProperty.validatePropertyFields(property, waterConnectionRequest.getRequestInfo());
		BusinessService businessService = workflowService.getBusinessService(
				waterConnectionRequest.getWaterConnection().getTenantId(), waterConnectionRequest.getRequestInfo(),
				config.getBusinessServiceValue());
		WaterConnection searchResult = getConnectionForUpdateRequest(
				waterConnectionRequest.getWaterConnection().getId(), waterConnectionRequest.getRequestInfo());
		String previousApplicationStatus = workflowService.getApplicationStatus(waterConnectionRequest.getRequestInfo(),
				waterConnectionRequest.getWaterConnection().getApplicationNo(),
				waterConnectionRequest.getWaterConnection().getTenantId(), config.getBusinessServiceValue());
		enrichmentService.enrichUpdateWaterConnection(waterConnectionRequest);
		actionValidator.validateUpdateRequest(waterConnectionRequest, businessService, previousApplicationStatus);
		waterConnectionValidator.validateUpdate(waterConnectionRequest, searchResult, WCConstants.UPDATE_APPLICATION);
		userService.updateUser(waterConnectionRequest, searchResult);
		// Call workflow
//		wfIntegrator.callWorkFlow(waterConnectionRequest, property);
		// call calculator service to generate the demand for one time fee
		if (waterConnectionRequest.getWaterConnection().getArrears() != null
				&& waterConnectionRequest.getWaterConnection().getArrears().intValue() > 0) {
			calculationService.calculateFeeAndGenerateDemand(waterConnectionRequest, property);
		}
		// check for edit and send edit notification
		waterDaoImpl.pushForEditNotification(waterConnectionRequest);
		// Enrich file store Id After payment
		enrichmentService.enrichFileStoreIds(waterConnectionRequest);
		userService.createUser(waterConnectionRequest);
		enrichmentService.postStatusEnrichment(waterConnectionRequest);
		boolean isStateUpdatable = waterServiceUtil.getStatusForUpdate(businessService, previousApplicationStatus);
		waterDao.updateWaterConnection(waterConnectionRequest, isStateUpdatable);
		enrichmentService.postForMeterReading(waterConnectionRequest, WCConstants.UPDATE_APPLICATION);
		return Arrays.asList(waterConnectionRequest.getWaterConnection());
	}

	/**
	 * Search Water connection to be update
	 * 
	 * @param id
	 * @param requestInfo
	 * @return water connection
	 */
	public WaterConnection getConnectionForUpdateRequest(String id, RequestInfo requestInfo) {
		Set<String> ids = new HashSet<>(Arrays.asList(id));
		SearchCriteria criteria = new SearchCriteria();
		criteria.setIds(ids);
		WaterConnectionResponse waterConnection = getWaterConnectionsList(criteria, requestInfo);

		if (CollectionUtils.isEmpty(waterConnection.getWaterConnection())) {
			StringBuilder builder = new StringBuilder();
			builder.append("WATER CONNECTION NOT FOUND FOR: ").append(id).append(" :ID");
			throw new CustomException("INVALID_WATERCONNECTION_SEARCH", builder.toString());
		}

		return waterConnection.getWaterConnection().get(0);
	}

	private List<WaterConnection> getAllWaterApplications(WaterConnectionRequest waterConnectionRequest) {
		SearchCriteria criteria = SearchCriteria.builder()
				.connectionNumber(waterConnectionRequest.getWaterConnection().getConnectionNo()).build();
		WaterConnectionResponse waterConnection = search(criteria, waterConnectionRequest.getRequestInfo());
		return waterConnection.getWaterConnection();
	}

	private List<WaterConnection> updateWaterConnectionForModifyFlow(WaterConnectionRequest waterConnectionRequest) {
		waterConnectionValidator.validateWaterConnection(waterConnectionRequest, WCConstants.MODIFY_CONNECTION);
		mDMSValidator.validateMasterData(waterConnectionRequest, WCConstants.MODIFY_CONNECTION);
		BusinessService businessService = workflowService.getBusinessService(
				waterConnectionRequest.getWaterConnection().getTenantId(), waterConnectionRequest.getRequestInfo(),
				config.getModifyWSBusinessServiceName());
		WaterConnection searchResult = getConnectionForUpdateRequest(
				waterConnectionRequest.getWaterConnection().getId(), waterConnectionRequest.getRequestInfo());
		Property property = validateProperty.getOrValidateProperty(waterConnectionRequest);
		validateProperty.validatePropertyFields(property, waterConnectionRequest.getRequestInfo());
		String previousApplicationStatus = workflowService.getApplicationStatus(waterConnectionRequest.getRequestInfo(),
				waterConnectionRequest.getWaterConnection().getApplicationNo(),
				waterConnectionRequest.getWaterConnection().getTenantId(), config.getModifyWSBusinessServiceName());
		enrichmentService.enrichUpdateWaterConnection(waterConnectionRequest);
		actionValidator.validateUpdateRequest(waterConnectionRequest, businessService, previousApplicationStatus);
		userService.updateUser(waterConnectionRequest, searchResult);
		waterConnectionValidator.validateUpdate(waterConnectionRequest, searchResult, WCConstants.MODIFY_CONNECTION);
		// call calculator service to generate the demand for one time fee
		if (waterConnectionRequest.getWaterConnection().getArrears() != null
				&& waterConnectionRequest.getWaterConnection().getArrears().intValue() > 0) {
			calculationService.calculateFeeAndGenerateDemand(waterConnectionRequest, property);
		}
//		wfIntegrator.callWorkFlow(waterConnectionRequest, property);
		boolean isStateUpdatable = waterServiceUtil.getStatusForUpdate(businessService, previousApplicationStatus);
		waterDao.updateWaterConnection(waterConnectionRequest, isStateUpdatable);
		// setting oldApplication Flag
		markOldApplication(waterConnectionRequest);
		// check for edit and send edit notification
		waterDaoImpl.pushForEditNotification(waterConnectionRequest);
		enrichmentService.postForMeterReading(waterConnectionRequest, WCConstants.MODIFY_CONNECTION);
		return Arrays.asList(waterConnectionRequest.getWaterConnection());
	}

	public void markOldApplication(WaterConnectionRequest waterConnectionRequest) {
		if (waterConnectionRequest.getWaterConnection().getProcessInstance().getAction()
				.equalsIgnoreCase(APPROVE_CONNECTION)) {
			String currentModifiedApplicationNo = waterConnectionRequest.getWaterConnection().getApplicationNo();
			List<WaterConnection> previousConnectionsList = getAllWaterApplications(waterConnectionRequest);

			for (WaterConnection waterConnection : previousConnectionsList) {
				if (!waterConnection.getOldApplication()
						&& !(waterConnection.getApplicationNo().equalsIgnoreCase(currentModifiedApplicationNo))) {
					waterConnection.setOldApplication(Boolean.TRUE);
					WaterConnectionRequest previousWaterConnectionRequest = WaterConnectionRequest.builder()
							.requestInfo(waterConnectionRequest.getRequestInfo()).waterConnection(waterConnection)
							.build();
					waterDao.updateWaterConnection(previousWaterConnectionRequest, Boolean.TRUE);
				}
			}
		}
	}

	@Override
	public void submitFeedback(FeedbackRequest feedbackrequest) {
		// TODO Auto-generated method stub
		mDMSValidator.validateQuestion(feedbackrequest);
		BillingCycle billingCycle = waterDaoImpl.getBillingCycle(feedbackrequest.getFeedback().getPaymentId());
		Date fromdate = new Date(billingCycle.getFromperiod());
		Date toDate = new Date(billingCycle.getToperiod());
		SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
		String formattedFromDate = formatter.format(fromdate);
		String formattedToDate = formatter.format(toDate);
		feedbackrequest.getFeedback().setId(UUID.randomUUID().toString());
		feedbackrequest.getFeedback().setBillingCycle(formattedFromDate + "-" + formattedToDate);

		if (feedbackrequest.getFeedback().getAuditDetails() == null) {
			AuditDetails auditDetails = new AuditDetails();
//			auditDetails.setCreatedBy(feedbackrequest.getRequestInfo().getUserInfo().getId().toString());
			auditDetails.setCreatedTime(new Date().getTime());
			auditDetails.setLastModifiedTime(new Date().getTime());
			feedbackrequest.getFeedback().setAuditDetails(auditDetails);
		}

		waterConnectionProducer.push(config.getSaveFeedback(), feedbackrequest);
	}

	@Override
	public Object getFeedback(FeedbackSearchCriteria feedbackSearchCriteria)
			throws JsonMappingException, JsonProcessingException {
		// TODO Auto-generated method stub
		List<Feedback> feedbackList = waterDaoImpl.getFeebback(feedbackSearchCriteria);

		Object data = getFeedBackRatingsAvarage(feedbackList);
		return data;
	}

	private Map<String, Integer> getFeedBackRatingsAvarage(List<Feedback> feedbackList)
			throws JsonMappingException, JsonProcessingException {

		Map<Object, Double> feedbackGroupByCode = null;
		Map<String, Integer> returnMap = new HashMap<String, Integer>();
		// TODO Auto-generated method stub
		List<CheckList> checkList = new ArrayList<CheckList>();
		ObjectMapper mapper = new ObjectMapper();
		for (Feedback feedback : feedbackList) {

			if (feedback.getAdditionalDetails() != null) {

				ObjectNode additionalDetails = (ObjectNode) feedback.getAdditionalDetails();
				if (additionalDetails.get("CheckList") != null) {
					List<CheckList> data = mapper.readValue(additionalDetails.get("CheckList").toString(),
							new TypeReference<List<CheckList>>() {
							});
					checkList.addAll(data);

				}
			}

		}

		if (checkList.size() > 0) {

			feedbackGroupByCode = checkList.stream()
					.collect(Collectors.groupingBy(e -> e.getCode(), Collectors.averagingInt(CheckList::getValue)));
		}
		if (!CollectionUtils.isEmpty(feedbackGroupByCode)) {
			for (Map.Entry<Object, Double> entry : feedbackGroupByCode.entrySet()) {
				returnMap.put(entry.getKey().toString(), entry.getValue().intValue());
			}
			returnMap.put("count", feedbackList.size());
		}

		return returnMap;
	}

	public LastMonthSummary getLastMonthSummary(SearchCriteria criteria, RequestInfo requestInfo) {

		LastMonthSummary lastMonthSummary = new LastMonthSummary();
		String tenantId = criteria.getTenantId();
		LocalDate currentMonthDate = LocalDate.now();
		if (criteria.getCurrentDate() != null) {
			Calendar currentDate = Calendar.getInstance();
			currentDate.setTimeInMillis(criteria.getCurrentDate());
			currentMonthDate = LocalDate.of(currentDate.get(Calendar.YEAR), currentDate.get(Calendar.MONTH) + 1,
					currentDate.get(Calendar.DAY_OF_MONTH));
		}
		LocalDate prviousMonthStart = currentMonthDate.minusMonths(1).with(TemporalAdjusters.firstDayOfMonth());
		LocalDate prviousMonthEnd = currentMonthDate.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());

		LocalDateTime previousMonthStartDateTime = LocalDateTime.of(prviousMonthStart.getYear(),
				prviousMonthStart.getMonth(), prviousMonthStart.getDayOfMonth(), 0, 0, 0);
		LocalDateTime previousMonthEndDateTime = LocalDateTime.of(prviousMonthEnd.getYear(), prviousMonthEnd.getMonth(),
				prviousMonthEnd.getDayOfMonth(), 23, 59, 59);

		// pending ws collectioni
		Integer cumulativePendingCollection = repository.getTotalPendingCollection(tenantId);
		if (null != cumulativePendingCollection)
			lastMonthSummary.setCumulativePendingCollection(cumulativePendingCollection.toString());

		// ws demands in period
		Integer newDemand = repository.getNewDemand(tenantId,
				((Long) previousMonthStartDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
				((Long) previousMonthEndDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
		if (null != newDemand)
			lastMonthSummary.setNewDemand(newDemand.toString());

		// actuall ws collection
		Integer actualCollection = repository.getActualCollection(tenantId,
				((Long) previousMonthStartDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
				((Long) previousMonthEndDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
		if (null != actualCollection)
			lastMonthSummary.setActualCollection(actualCollection.toString());

		lastMonthSummary.setPreviousMonthYear(getMonthYear());

		return lastMonthSummary;

	}

	public String getMonthYear() {
		LocalDateTime localDateTime = LocalDateTime.now();
		int currentMonth = localDateTime.getMonthValue();
		String monthYear;
		if (currentMonth >= Month.APRIL.getValue()) {
			monthYear = YearMonth.now().getYear() + "-";
			monthYear = monthYear
					+ (Integer.toString(YearMonth.now().getYear() + 1).substring(2, monthYear.length() - 1));
		} else {
			monthYear = YearMonth.now().getYear() - 1 + "-";
			monthYear = monthYear + (Integer.toString(YearMonth.now().getYear()).substring(2, monthYear.length() - 1));

		}
		localDateTime.minusMonths(1);
		StringBuilder monthYearBuilder = new StringBuilder(localDateTime.getMonth().toString()).append(" ")
				.append(monthYear);

		return monthYearBuilder.toString();
	}

	@Override
	public RevenueDashboard getRevenueDashboardData(@Valid SearchCriteria criteria, RequestInfo requestInfo) {
		RevenueDashboard dashboardData = new RevenueDashboard();
		String tenantId = criteria.getTenantId();
		Integer demand = waterDaoImpl.getTotalDemandAmount(criteria);
		if (null != demand) {
			dashboardData.setDemand(demand.toString());
		}
		Integer paidAmount = waterDaoImpl.getActualCollectionAmount(criteria);
		if (null != paidAmount) {
			dashboardData.setActualCollection(paidAmount.toString());
		}
		Integer unpaidAmount = waterDaoImpl.getPendingCollectionAmount(criteria);
		if (null != unpaidAmount) {
			dashboardData.setPendingCollection(unpaidAmount.toString());
		}

		return dashboardData;

	}

	@Override
	public WaterConnectionResponse getWCListFuzzySearch(SearchCriteria criteria, RequestInfo requestInfo) {
		 
		List<String> idsfromDB = waterDao.getWCListFuzzySearch(criteria);
		
		 if(CollectionUtils.isEmpty(idsfromDB))
			 WaterConnectionResponse.builder().waterConnection(new LinkedList<>());

	     validateFuzzySearchCriteria(criteria);
		
		 Object esResponse = elasticSearchRepository.fuzzySearchProperties(criteria, idsfromDB);
		
		 List<Map<String, Object>> data = wsDataResponse(esResponse);
		 
		 return WaterConnectionResponse.builder().waterConnectionData(data).totalCount(data.size()).build();
	}
	
	private void validateFuzzySearchCriteria(SearchCriteria criteria){

        if(org.apache.commons.lang3.StringUtils.isBlank(criteria.getName()) && org.apache.commons.lang3.StringUtils.isBlank(criteria.getMobileNumber()))
            throw new CustomException("EG_WC_SEARCH_ERROR"," No criteria given for the WC search");
    }
	
	private List<Map<String, Object>> wsDataResponse(Object esResponse) {

	        List<Map<String, Object>> data;
	        try {
	            data = JsonPath.read(esResponse, WCConstants.ES_DATA_PATH);
	        } catch (Exception e) {
	            throw new CustomException("PARSING_ERROR", "Failed to extract data from es response");
	        }

	        return data;
	    }
}