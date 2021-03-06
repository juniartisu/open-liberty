/*******************************************************************************
 * Copyright (c) 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.jbatch.container.services.impl;

import java.io.Writer;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.batch.operations.BatchRuntimeException;
import javax.batch.operations.JobRestartException;
import javax.batch.operations.NoSuchJobExecutionException;
import javax.batch.operations.NoSuchJobInstanceException;
import javax.batch.runtime.BatchStatus;
import javax.batch.runtime.JobExecution;
import javax.batch.runtime.JobInstance;
import javax.batch.runtime.StepExecution;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.transaction.NotSupportedException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.Transaction;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import com.ibm.jbatch.container.RASConstants;
import com.ibm.jbatch.container.exception.BatchIllegalIDPersistedException;
import com.ibm.jbatch.container.exception.BatchIllegalJobStatusTransitionException;
import com.ibm.jbatch.container.exception.ExecutionAssignedToServerException;
import com.ibm.jbatch.container.exception.JobStoppedException;
import com.ibm.jbatch.container.exception.PersistenceException;
import com.ibm.jbatch.container.execution.impl.RuntimeStepExecution;
import com.ibm.jbatch.container.persistence.jpa.JobExecutionEntity;
import com.ibm.jbatch.container.persistence.jpa.JobExecutionEntityV2;
import com.ibm.jbatch.container.persistence.jpa.JobInstanceEntity;
import com.ibm.jbatch.container.persistence.jpa.JobInstanceEntityV2;
import com.ibm.jbatch.container.persistence.jpa.RemotablePartitionEntity;
import com.ibm.jbatch.container.persistence.jpa.RemotablePartitionKey;
import com.ibm.jbatch.container.persistence.jpa.StepThreadExecutionEntity;
import com.ibm.jbatch.container.persistence.jpa.StepThreadInstanceEntity;
import com.ibm.jbatch.container.persistence.jpa.StepThreadInstanceKey;
import com.ibm.jbatch.container.persistence.jpa.TopLevelStepExecutionEntity;
import com.ibm.jbatch.container.persistence.jpa.TopLevelStepInstanceEntity;
import com.ibm.jbatch.container.persistence.jpa.TopLevelStepInstanceKey;
import com.ibm.jbatch.container.services.IJPAQueryHelper;
import com.ibm.jbatch.container.services.IPersistenceManagerService;
import com.ibm.jbatch.container.util.WSStepThreadExecutionAggregateImpl;
import com.ibm.jbatch.container.ws.BatchLocationService;
import com.ibm.jbatch.container.ws.InstanceState;
import com.ibm.jbatch.container.ws.RemotablePartitionState;
import com.ibm.jbatch.container.ws.WSPartitionStepThreadExecution;
import com.ibm.jbatch.container.ws.WSStepThreadExecutionAggregate;
import com.ibm.jbatch.container.ws.impl.WSStartupRecoveryServiceImpl;
import com.ibm.jbatch.spi.services.IBatchConfig;
import com.ibm.ws.LocalTransaction.LocalTransactionCoordinator;
import com.ibm.ws.LocalTransaction.LocalTransactionCurrent;
import com.ibm.ws.Transaction.UOWCurrent;
import com.ibm.ws.ffdc.annotation.FFDCIgnore;
import com.ibm.ws.tx.embeddable.EmbeddableWebSphereTransactionManager;
import com.ibm.wsspi.persistence.DDLGenerationParticipant;
import com.ibm.wsspi.persistence.DatabaseStore;
import com.ibm.wsspi.persistence.PersistenceServiceUnit;

/**
 * Note: JPAPersistenceManagerImpl is ranked higher than MemoryPeristenceManagerImpl
 * so if they're both activated, JPA should take precedence. Note that all @Reference
 * injectors of IPersistenceManagerService should set the GREEDY option so that they
 * always get injected with JPA over Memory if it's available.
 */
@Component(configurationPid = "com.ibm.ws.jbatch.container.persistence", service = { IPersistenceManagerService.class,
                                                                                     DDLGenerationParticipant.class,
}, configurationPolicy = ConfigurationPolicy.REQUIRE, property = { "service.vendor=IBM",
                                                                   "service.ranking:Integer=20",
                                                                   "persistenceType=JPA" })
public class JPAPersistenceManagerImpl extends AbstractPersistenceManager implements IPersistenceManagerService, DDLGenerationParticipant {

    private final static Logger logger = Logger.getLogger(JPAPersistenceManagerImpl.class.getName(),
                                                          RASConstants.BATCH_MSG_BUNDLE);

    /**
     * For wrapping jpa calls in trans
     */
    private EmbeddableWebSphereTransactionManager tranMgr;

    /**
     * For controlling local transactions
     */
    private LocalTransactionCurrent localTranCurrent;

    /**
     * Persistent store for batch runtime DB.
     */
    private DatabaseStore databaseStore;

    /**
     * config.displayId for the database store configuration element.
     */
    private String databaseStoreDisplayId;

    /**
     * For resolving the batch REST url and serverId of this server.
     */
    private BatchLocationService batchLocationService;

    /**
     * For async operations, such as the initial setup of the JPA datastore
     */
    private ExecutorService executorService;

    /**
     * Persistence service unit. Gets initiated lazily upon first access.
     * For the details on why we chose lazy activation for this, see defect 166203.
     *
     * Note: marked 'volatile' to avoid problems with double-checked locking algorithms
     * (see http://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html)
     */
    private volatile PersistenceServiceUnit psu;

    /**
     * Used to cache the result of our job execution table V2 check.
     */
    private Integer executionVersion = null;

    /**
     * Used to cache the result of our job instance table V2 check.
     */
    private Integer instanceVersion = null;

    /**
     * Most current versions of entities.
     */
    private static final int MAX_EXECUTION_VERSION = 2;
    private static final int MAX_INSTANCE_VERSION = 2;

    /**
     * Declarative Services method for setting the Liberty executor.
     *
     * @param svc the service
     */
    @Reference(target = "(component.name=com.ibm.ws.threading)")
    protected void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    /**
     * DS inject
     */
    @Reference(name = "jobStore", target = "(id=unbound)")
    protected void setDatabaseStore(DatabaseStore databaseStore, Map<String, Object> props) {
        this.databaseStore = databaseStore;
        this.databaseStoreDisplayId = (String) props.get("config.displayId");
    }

    /**
     * DS inject
     */
    @Reference
    protected void setTransactionManager(EmbeddableWebSphereTransactionManager svc) {
        tranMgr = svc;
    }

    /**
     * DS inject
     */
    @Reference
    protected void setLocalTransactionCurrent(LocalTransactionCurrent ltc) {
        localTranCurrent = ltc;
    }

    /**
     * DS injection
     */
    @Reference
    protected void setBatchLocationService(BatchLocationService batchLocationService) {
        this.batchLocationService = batchLocationService;
    }

    /**
     * DS activate
     */
    @Activate
    protected void activate(ComponentContext context, Map<String, Object> config) {
        logger.log(Level.INFO, "persistence.service.status", new Object[] { "JPA", "activated" });
    }

    /**
     * DS deactivate
     */
    @Deactivate
    protected void deactivate() {

        if (psu != null) {
            try {
                psu.close();
            } catch (Exception e) {
                // FFDC.
            }
        }

        logger.log(Level.INFO, "persistence.service.status", new Object[] { "JPA", "deactivated" });
    }

    /* Interface methods */

    @Override
    public void init(IBatchConfig batchConfig) {}

    @Override
    public void shutdown() {}

    //private static boolean writtenDDL = false;

    /**
     * @return the PersistenceServiceUnit.
     */
    private PersistenceServiceUnit getPsu() {
        if (psu == null) {
            try {
                psu = createPsu();
            } catch (Exception e) {
                throw new BatchRuntimeException("Failed to load JPA PersistenceServiceUnit", e);
            }
        }
        return psu;
    }

    /**
     * Creates a PersistenceServiceUnit using the specified entity versions.
     */
    private PersistenceServiceUnit createPsu(int jobInstanceVersion, int jobExecutionVersion) throws Exception {
        return databaseStore.createPersistenceServiceUnit(getJobInstanceEntityClass(jobInstanceVersion).getClassLoader(),
                                                          getJobExecutionEntityClass(jobExecutionVersion).getName(),
                                                          getJobInstanceEntityClass(jobInstanceVersion).getName(),
                                                          StepThreadExecutionEntity.class.getName(),
                                                          StepThreadInstanceEntity.class.getName(),
                                                          TopLevelStepExecutionEntity.class.getName(),
                                                          TopLevelStepInstanceEntity.class.getName());
    }

    /**
     * Creates a PersistenceServiceUnit using the most recent entities.
     */
    private PersistenceServiceUnit createLatestPsu() throws Exception {
        return createPsu(MAX_INSTANCE_VERSION, MAX_EXECUTION_VERSION);
    }

    /**
     * @param jobExecutionVersion
     * @return job execution entity class
     */
    @SuppressWarnings("rawtypes")
    private Class getJobExecutionEntityClass(int jobExecutionVersion) {
        if (jobExecutionVersion >= 2) {
            return JobExecutionEntityV2.class;
        } else {
            return JobExecutionEntity.class;
        }
    }

    /**
     * @param jobInstanceVersion
     * @return job instance entity class
     */
    @SuppressWarnings("rawtypes")
    private Class getJobInstanceEntityClass(int jobInstanceVersion) {
        if (jobInstanceVersion >= 2) {
            return JobInstanceEntityV2.class;
        } else {
            return JobInstanceEntity.class;
        }
    }

    /**
     * @return create and return the PSU.
     *
     * @throws Exception
     */
    private synchronized PersistenceServiceUnit createPsu() throws Exception {
        if (psu != null) {
            return psu;
        }

        // Load the PSU including the most recent entities.
        PersistenceServiceUnit retMe = createLatestPsu();

        // If any tables are not up to the current code level, re-load the PSU with backleveled entities.
        int instanceVersion = getJobInstanceTableVersion(retMe);
        if (instanceVersion < 2) {
            logger.fine("The UPDATETIME column could not be found. The persistence service unit will exclude the V2 instance entity.");
            retMe.close();
            retMe = createPsu(instanceVersion, MAX_EXECUTION_VERSION);
        }

        int executionVersion = getJobExecutionTableVersion(retMe);
        if (executionVersion < 2) {
            logger.fine("The JOBPARAMETERS table could not be found. The persistence service unit will exclude the V2 execution entity.");
            retMe.close();
            retMe = createPsu(instanceVersion, executionVersion);
        }

        //222050 - Backout 205106 RemotablePartitionEntity.class.getName());

        // Perform recovery immediately, before returning from this method, so that
        // other callers won't be able to access the PSU (via getPsu()) until recovery is complete.
        new WSStartupRecoveryServiceImpl().setIPersistenceManagerService(JPAPersistenceManagerImpl.this).setPersistenceServiceUnit(retMe).recoverLocalJobsInInflightStates();
        //222050 - Backout 205106 .recoverLocalPartitionsInInflightStates();

        // Make sure we assign psu before leaving the synchronized block.
        psu = retMe;

        return psu;
    }

    //
    // BEGINNING OF INTERFACE IMPL
    //

    @Override
    public String getDisplayId() {
        // display id will be formatted like: databaseStore[BatchDatabaseStore]
        // get the actual ref name out of there, in this case: BatchDatabaseStore
        Pattern pattern = Pattern.compile(".*\\[(.*)\\]");
        Matcher matcher = pattern.matcher(databaseStoreDisplayId);
        matcher.find();
        return matcher.group(1);
    }

    @Override
    public String getPersistenceType() {
        return "JPA";
    }

    @Override
    public JobInstanceEntity createJobInstance(final String appName, final String jobXMLName, final String submitter, final Date createTime) {

        return this.createJobInstance(appName, jobXMLName, null, submitter, createTime);
    }

    @Override
    public JobInstanceEntity createJobInstance(final String appName, final String jobXMLName, final String jsl, final String submitter, final Date createTime) {

        final EntityManager em = getPsu().createEntityManager();
        try {

            JobInstanceEntity instance = new TranRequest<JobInstanceEntity>(em) {
                @Override
                public JobInstanceEntity call() {
                    JobInstanceEntity jobInstance;
                    if (instanceVersion >= 2) {
                        jobInstance = new JobInstanceEntityV2();
                    } else {
                        jobInstance = new JobInstanceEntity();
                    }
                    jobInstance.setAmcName(appName);
                    jobInstance.setJobXmlName(jobXMLName);
                    jobInstance.setJobXml(jsl);
                    jobInstance.setSubmitter(submitter);
                    jobInstance.setCreateTime(createTime);
                    jobInstance.setLastUpdatedTime(createTime);
                    jobInstance.setInstanceState(InstanceState.SUBMITTED);
                    jobInstance.setBatchStatus(BatchStatus.STARTING); // Not sure how important the batch status is, the instance state is more important.  I guess we'll set it.
                    entityMgr.persist(jobInstance);
                    return jobInstance;
                }
            }.runInNewOrExistingGlobalTran();

            validatePersistedJobInstanceIds(instance);
            return instance;

        } finally {
            em.close();
        }
    }

    @Override
    public JobInstanceEntity getJobInstance(long jobInstanceId) throws NoSuchJobInstanceException {
        EntityManager em = getPsu().createEntityManager();
        try {
            JobInstanceEntity instance = em.find(JobInstanceEntity.class, jobInstanceId);
            if (instance == null) {
                throw new NoSuchJobInstanceException("No job instance found for id = " + jobInstanceId);
            }
            return instance;
        } finally {
            em.close();
        }
    }

    @Override
    public JobInstanceEntity getJobInstanceFromExecutionId(long jobExecutionId) throws NoSuchJobExecutionException {
        EntityManager em = getPsu().createEntityManager();
        try {
            JobExecutionEntity exec = em.find(JobExecutionEntity.class, jobExecutionId);
            if (exec == null) {
                throw new NoSuchJobExecutionException("No job execution found for id = " + jobExecutionId);
            }
            return exec.getJobInstance();
        } finally {
            em.close();
        }
    }

    @Override
    public List<JobInstanceEntity> getJobInstances(String jobName, int start, int count) {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<JobInstanceEntity> query = em.createNamedQuery(JobInstanceEntity.GET_JOBINSTANCES_SORT_CREATETIME_BY_JOBNAME_QUERY,
                                                                      JobInstanceEntity.class);
            query.setParameter("name", jobName);
            List<JobInstanceEntity> ids = query.setFirstResult(start).setMaxResults(count).getResultList();
            if (ids == null) {
                return new ArrayList<JobInstanceEntity>();
            }
            return ids;
        } finally {
            em.close();
        }
    }

    @Override
    public List<JobInstanceEntity> getJobInstances(String jobName, String submitter, int start, int count) {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<JobInstanceEntity> query = em.createNamedQuery(JobInstanceEntity.GET_JOBINSTANCES_SORT_CREATETIME_BY_JOBNAME_AND_SUBMITTER_QUERY,
                                                                      JobInstanceEntity.class);
            query.setParameter("name", jobName);
            query.setParameter("submitter", submitter);
            List<JobInstanceEntity> ids = query.setFirstResult(start).setMaxResults(count).getResultList();
            if (ids == null) {
                return new ArrayList<JobInstanceEntity>();
            }
            return ids;
        } finally {
            em.close();
        }
    }

    @Override
    public List<JobInstanceEntity> getJobInstances(int page, int pageSize) {
        ArrayList<JobInstanceEntity> result = new ArrayList<JobInstanceEntity>();
        List<JobInstanceEntity> jobList;
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<JobInstanceEntity> query = em.createNamedQuery(JobInstanceEntity.GET_JOBINSTANCES_SORT_BY_CREATETIME_FIND_ALL_QUERY,
                                                                      JobInstanceEntity.class);
            jobList = query.setFirstResult(page * pageSize).setMaxResults(pageSize).getResultList();

            if (jobList != null) {
                for (JobInstanceEntity instance : jobList) {
                    result.add(instance);
                }
            }

            return result;
        } finally {
            em.close();
        }
    }

    @Override
    public List<JobInstanceEntity> getJobInstances(IJPAQueryHelper queryHelper, int page, int pageSize) {
        ArrayList<JobInstanceEntity> result = new ArrayList<JobInstanceEntity>();
        List<JobInstanceEntity> jobList;
        EntityManager em = getPsu().createEntityManager();
        try {

            // Obtain the JPA query from the Helper
            String jpaQueryString = queryHelper.getQuery();

            // Build and populate the parameters of the JPA query
            TypedQuery<JobInstanceEntity> query = em.createQuery(jpaQueryString, JobInstanceEntity.class);

            queryHelper.setQueryParameters(query);

            jobList = query.setFirstResult(page * pageSize).setMaxResults(pageSize).getResultList();

            if (jobList != null) {
                for (JobInstanceEntity instance : jobList) {
                    result.add(instance);
                }
            }

            return result;
        } finally {
            em.close();
        }
    }

    @Override
    public List<JobInstanceEntity> getJobInstances(int page, int pageSize, String submitter) {

        ArrayList<JobInstanceEntity> result = new ArrayList<JobInstanceEntity>();
        List<JobInstanceEntity> jobList;
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<JobInstanceEntity> query = em.createNamedQuery(JobInstanceEntity.GET_JOBINSTANCES_SORT_BY_CREATETIME_FIND_BY_SUBMITTER_QUERY,
                                                                      JobInstanceEntity.class);
            query.setParameter("submitter", submitter);
            jobList = query.setFirstResult(page * pageSize).setMaxResults(pageSize).getResultList();

            if (jobList != null) {
                for (JobInstanceEntity instance : jobList) {
                    result.add(instance);
                }
            }
            return result;
        } finally {
            em.close();
        }
    }

    @Override
    public Set<String> getJobNamesSet() {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<String> query = em.createNamedQuery(JobInstanceEntity.GET_JOB_NAMES_SET_QUERY,
                                                           String.class);
            List<String> result = query.getResultList();
            if (result == null) {
                return new HashSet<String>();
            }
            return cleanUpResult(new HashSet<String>(result));
        } finally {
            em.close();
        }
    }

    @Override
    public Set<String> getJobNamesSet(String submitter) {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<String> query = em.createNamedQuery(JobInstanceEntity.GET_JOB_NAMES_SET_BY_SUBMITTER_QUERY,
                                                           String.class);
            query.setParameter("submitter", submitter);
            List<String> result = query.getResultList();
            if (result == null) {
                return new HashSet<String>();
            }
            return cleanUpResult(new HashSet<String>(result));
        } finally {
            em.close();
        }
    }

    /*
     * Remove any null values.
     */
    private Set<String> cleanUpResult(Set<String> s) {
        s.remove(null);
        return s;
    }

    @Override
    public int getJobInstanceCount(String jobName) {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<Long> query = em.createNamedQuery(JobInstanceEntity.GET_JOBINSTANCE_COUNT_BY_JOBNAME_QUERY,
                                                         Long.class);
            query.setParameter("name", jobName);
            Long result = query.getSingleResult();
            if (result > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("More than MAX_INTEGER results found.");
            } else {
                return result.intValue();
            }
        } finally {
            em.close();
        }
    }

    @Override
    public int getJobInstanceCount(String jobName, String submitter) {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<Long> query = em.createNamedQuery(JobInstanceEntity.GET_JOBINSTANCE_COUNT_BY_JOBNAME_AND_SUBMITTER_QUERY,
                                                         Long.class);
            query.setParameter("name", jobName);
            query.setParameter("submitter", submitter);
            Long result = query.getSingleResult();
            if (result > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("More than MAX_INTEGER results found.");
            } else {
                return result.intValue();
            }
        } finally {
            em.close();
        }
    }

    @Override
    public JobInstance updateJobInstanceWithInstanceState(final long jobInstanceId, final InstanceState state, final Date lastUpdated) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<JobInstance>(em) {
                @Override
                public JobInstance call() {
                    JobInstanceEntity instance = entityMgr.find(JobInstanceEntity.class, jobInstanceId);
                    if (instance == null) {
                        throw new NoSuchJobInstanceException("No job instance found for id = " + jobInstanceId);
                    }

                    try {
                        verifyStateTransitionIsValid(instance, state);
                    } catch (BatchIllegalJobStatusTransitionException e) {
                        throw new PersistenceException(e);
                    }

                    instance.setInstanceState(state);
                    instance.setLastUpdatedTime(lastUpdated);
                    return instance;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobInstance updateJobInstanceWithInstanceStateUponRestart(final long jobInstanceId, final InstanceState state, final Date lastUpdated) {
        EntityManager em = getPsu().createEntityManager();
        String BASE_UPDATE = "UPDATE JobInstanceEntity x SET x.instanceState = :instanceState,x.batchStatus = :batchStatus";
        if (instanceVersion >= 2)
            BASE_UPDATE = BASE_UPDATE.replace("JobInstanceEntity", "JobInstanceEntityV2").concat(",x.lastUpdatedTime = :lastUpdatedTime");
        StringBuilder query = new StringBuilder().append(BASE_UPDATE);
        StringBuilder whereClause = new StringBuilder();

        whereClause.append("x.instanceId = :instanceId");
        whereClause.append(" AND x.instanceState IN (com.ibm.jbatch.container.ws.InstanceState.STOPPED,");
        whereClause.append(" com.ibm.jbatch.container.ws.InstanceState.FAILED)");

        query.append(" WHERE " + whereClause);
        final String FINAL_UPDATE = query.toString();

        try {
            return new TranRequest<JobInstance>(em) {
                @Override
                public JobInstance call() {
                    JobInstanceEntity instance = entityMgr.find(JobInstanceEntity.class, jobInstanceId);
                    if (instance == null) {
                        throw new NoSuchJobInstanceException("No job instance found for id = " + jobInstanceId);
                    }

                    try {
                        verifyStateTransitionIsValid(instance, state);
                        verifyStatusTransitionIsValid(instance, BatchStatus.STARTING);
                    } catch (BatchIllegalJobStatusTransitionException e) {
                        throw new PersistenceException(e);
                    }

                    Query jpaQuery = entityMgr.createQuery(FINAL_UPDATE);
                    jpaQuery.setParameter("instanceState", state);
                    jpaQuery.setParameter("instanceId", jobInstanceId);
                    if (instanceVersion >= 2)
                        jpaQuery.setParameter("lastUpdatedTime", lastUpdated);
                    jpaQuery.setParameter("batchStatus", BatchStatus.STARTING);

                    int count = jpaQuery.executeUpdate();
                    if (count > 0) {
                        // Need to refresh to pick up changes made to the database
                        entityMgr.refresh(instance);
                    } else {
                        String msg = "The job instance " + jobInstanceId + " cannot be restarted because it is still in a non-final state.";
                        throw new JobRestartException(msg);
                    }
                    return instance;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobInstance updateJobInstanceNullOutRestartOn(final long jobInstanceId) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<JobInstance>(em) {
                @Override
                public JobInstance call() {
                    JobInstanceEntity instance = entityMgr.find(JobInstanceEntity.class, jobInstanceId);
                    if (instance == null) {
                        throw new NoSuchJobInstanceException("No job instance found for id = " + jobInstanceId);
                    }
                    instance.setRestartOn(null);
                    return instance;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobInstance updateJobInstanceWithRestartOn(final long jobInstanceId, final String restartOn) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<JobInstance>(em) {
                @Override
                public JobInstance call() {
                    JobInstanceEntity instance = entityMgr.find(JobInstanceEntity.class, jobInstanceId);
                    if (instance == null) {
                        throw new NoSuchJobInstanceException("No job instance found for id = " + jobInstanceId);
                    }
                    instance.setRestartOn(restartOn);
                    return instance;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobInstance updateJobInstanceWithJobNameAndJSL(final long jobInstanceId, final String jobName, final String jobXml) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<JobInstance>(em) {
                @Override
                public JobInstance call() {
                    JobInstanceEntity instance = entityMgr.find(JobInstanceEntity.class, jobInstanceId);
                    if (instance == null) {
                        throw new NoSuchJobInstanceException("No job instance found for id = " + jobInstanceId);
                    }
                    instance.setJobName(jobName);
                    instance.setJobXml(jobXml);
                    return instance;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobExecution updateJobExecutionAndInstanceOnStarted(final long jobExecutionId, final Date startedTime) throws NoSuchJobExecutionException {
        EntityManager em = getPsu().createEntityManager();
        try {
            JobExecution exec = new TranRequest<JobExecution>(em) {
                @Override
                public JobExecution call() {
                    JobExecutionEntity exec = entityMgr.find(JobExecutionEntity.class, jobExecutionId);
                    if (exec == null) {
                        throw new NoSuchJobExecutionException("No job execution found for id = " + jobExecutionId);
                    }

                    try {
                        verifyStatusTransitionIsValid(exec, BatchStatus.STARTED);
                        verifyStateTransitionIsValid(exec.getJobInstance(), InstanceState.DISPATCHED);
                    } catch (BatchIllegalJobStatusTransitionException e) {
                        throw new PersistenceException(e);
                    }

                    exec.setBatchStatus(BatchStatus.STARTED);
                    exec.getJobInstance().setInstanceState(InstanceState.DISPATCHED);
                    exec.getJobInstance().setBatchStatus(BatchStatus.STARTED);
                    exec.getJobInstance().setLastUpdatedTime(startedTime);
                    exec.setStartTime(startedTime);
                    exec.setLastUpdatedTime(startedTime);
                    return exec;
                }
            }.runInNewOrExistingGlobalTran();

            return exec;

        } finally {
            em.close();
        }
    }

    /*
     * Check persisted job instance id and throw exception if any ids violate
     * batch id rule: Cannot be <= 0
     */
    protected void validatePersistedJobInstanceIds(JobInstanceEntity instance) throws PersistenceException {
        if (instance.getInstanceId() <= 0) {

            long id = instance.getInstanceId();

            PersistenceException e = new PersistenceException(new BatchIllegalIDPersistedException(Long.toString(id)));
            logger.log(Level.SEVERE, "error.invalid.persisted.job.id",
                       new Object[] { Long.toString(id), e });

            throw e;
        }

    }

    /*
     * Check persisted job execution id and throw exception if any ids violate
     * batch id rule: Cannot be <= 0
     */
    protected void validatePersistedJobExecution(JobExecutionEntity execution) throws PersistenceException {

        if (execution.getExecutionId() <= 0) {

            long exId = execution.getExecutionId();

            PersistenceException e = new PersistenceException(new BatchIllegalIDPersistedException(Long.toString(exId)));
            logger.log(Level.SEVERE, "error.invalid.persisted.exe.id",
                       new Object[] { Long.toString(exId), e });

            throw e;
        }

    }

    /*
     * Check persisted job step execution id and throw exception if any ids
     * violate batch id rule: Cannot be <= 0
     */
    protected void validatePersistedStepExecution(
                                                  StepThreadExecutionEntity stepExecution) throws PersistenceException {

        if (stepExecution.getStepExecutionId() <= 0) {

            long stepId = stepExecution.getStepExecutionId();

            PersistenceException e = new PersistenceException(new BatchIllegalIDPersistedException(Long.toString(stepId)));
            logger.log(Level.SEVERE, "error.invalid.persisted.step.id",
                       new Object[] { Long.toString(stepId), e });

            throw e;
        }

    }

    @Override
    public JobExecution updateJobExecutionAndInstanceOnStatusChange(final long jobExecutionId, final BatchStatus newBatchStatus,
                                                                    final Date updateTime) throws NoSuchJobExecutionException {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<JobExecution>(em) {
                @Override
                public JobExecution call() {
                    JobExecutionEntity exec = entityMgr.find(JobExecutionEntity.class, jobExecutionId);
                    if (exec == null) {
                        throw new NoSuchJobExecutionException("No job execution found for id = " + jobExecutionId);
                    }

                    try {
                        verifyStatusTransitionIsValid(exec, newBatchStatus);
                    } catch (BatchIllegalJobStatusTransitionException e) {
                        throw new PersistenceException(e);
                    }

                    exec.setBatchStatus(newBatchStatus);
                    exec.getJobInstance().setBatchStatus(newBatchStatus);
                    exec.getJobInstance().setLastUpdatedTime(updateTime);
                    exec.setLastUpdatedTime(updateTime);
                    return exec;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobExecution updateJobExecutionAndInstanceNotSetToServerYet(final long jobExecutionId,
                                                                       final Date updateTime) throws NoSuchJobExecutionException, ExecutionAssignedToServerException {
        EntityManager em = getPsu().createEntityManager();

        final TypedQuery<JobExecutionEntity> query = em.createNamedQuery(JobExecutionEntity.UPDATE_JOB_EXECUTION_AND_INSTANCE_SERVER_NOT_SET,
                                                                         JobExecutionEntity.class);
        query.setParameter("batchStatus", BatchStatus.STOPPED);
        query.setParameter("jobExecId", jobExecutionId);
        query.setParameter("lastUpdatedTime", updateTime);
        try {
            return new TranRequest<JobExecution>(em) {
                @Override
                public JobExecution call() throws ExecutionAssignedToServerException {
                    JobExecutionEntity execution = entityMgr.find(JobExecutionEntity.class, jobExecutionId, LockModeType.PESSIMISTIC_WRITE);
                    if (execution == null) {
                        throw new NoSuchJobExecutionException("No job execution found for id = " + jobExecutionId);
                    }
                    JobInstanceEntity instance = entityMgr.find(JobInstanceEntity.class, execution.getInstanceId());
                    if (instance == null) {
                        throw new NoSuchJobInstanceException("No job instance found for id = " + execution.getInstanceId());
                    }

                    try {
                        verifyStatusTransitionIsValid(execution, BatchStatus.STOPPED);
                        verifyStateTransitionIsValid(instance, InstanceState.STOPPED);
                    } catch (BatchIllegalJobStatusTransitionException e) {
                        throw new PersistenceException(e);
                    }

                    instance.setBatchStatus(BatchStatus.STOPPED);
                    instance.setInstanceState(InstanceState.STOPPED);
                    instance.setLastUpdatedTime(updateTime);

                    int count = query.executeUpdate();
                    if (count > 0) {
                        // Need to refresh to pick up changes made to the database
                        entityMgr.refresh(execution);
                    } else {
                        String msg = "Job execution " + jobExecutionId + " is in an invalid state";
                        throw new ExecutionAssignedToServerException(msg);
                    }
                    return execution;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobExecution updateJobExecutionAndInstanceOnEnd(final long jobExecutionId, final BatchStatus finalBatchStatus, final String finalExitStatus,
                                                           final Date endTime) throws NoSuchJobExecutionException {
        return updateJobExecutionAndInstanceFinalStatus(getPsu(), jobExecutionId, finalBatchStatus, finalExitStatus, endTime);
    }

    /**
     * This method is called during recovery, as well as during normal operation.
     *
     * Note this is public but not part of the IPersistenceManagerService interface,
     * since there's no equivalent for in-mem persistence.
     *
     * Set the final batchStatus, exitStatus, and endTime for the given jobExecutionId.
     *
     */
    public JobExecution updateJobExecutionAndInstanceFinalStatus(PersistenceServiceUnit psu,
                                                                 final long jobExecutionId,
                                                                 final BatchStatus finalBatchStatus,
                                                                 final String finalExitStatus,
                                                                 final Date endTime) throws NoSuchJobExecutionException {
        EntityManager em = psu.createEntityManager();
        try {
            return new TranRequest<JobExecution>(em) {
                @Override
                public JobExecution call() {
                    JobExecutionEntity exec = entityMgr.find(JobExecutionEntity.class, jobExecutionId);
                    if (exec == null) {
                        throw new NoSuchJobExecutionException("No job execution found for id = " + jobExecutionId);
                    }

                    try {
                        verifyStatusTransitionIsValid(exec, finalBatchStatus);

                        exec.setBatchStatus(finalBatchStatus);
                        exec.getJobInstance().setBatchStatus(finalBatchStatus);
                        exec.setExitStatus(finalExitStatus);
                        exec.getJobInstance().setExitStatus(finalExitStatus);
                        exec.getJobInstance().setLastUpdatedTime(endTime);
                        // set the state to be the same value as the batchstatus
                        // Note: we only want to do this is if the batchStatus is one of the "done" statuses.
                        if (FINAL_STATUS_SET.contains(finalBatchStatus)) {
                            InstanceState newInstanceState = InstanceState.valueOf(finalBatchStatus.toString());

                            verifyStateTransitionIsValid(exec.getJobInstance(), newInstanceState);

                            exec.getJobInstance().setInstanceState(newInstanceState);
                        }
                        exec.setLastUpdatedTime(endTime);
                        exec.setEndTime(endTime);
                        return exec;
                    } catch (BatchIllegalJobStatusTransitionException e) {
                        throw new PersistenceException(e);
                    }

                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobExecutionEntity createJobExecution(final long jobInstanceId, Properties jobParameters, Date createTime) {
        final JobExecutionEntity jobExecution;

        if (executionVersion >= 2) {
            jobExecution = new JobExecutionEntityV2();
        } else {
            jobExecution = new JobExecutionEntity();
        }

        jobExecution.setCreateTime(createTime);
        jobExecution.setLastUpdatedTime(createTime);
        jobExecution.setBatchStatus(BatchStatus.STARTING);
        jobExecution.setJobParameters(jobParameters);
        jobExecution.setRestUrl(batchLocationService.getBatchRestUrl());

        EntityManager em = getPsu().createEntityManager();
        try {
            new TranRequest<Void>(em) {
                @Override
                public Void call() {
                    JobInstanceEntity jobInstance = entityMgr.find(JobInstanceEntity.class, jobInstanceId);
                    if (jobInstance == null) {
                        throw new IllegalStateException("Didn't find JobInstanceEntity associated with value: " + jobInstanceId);
                    }
                    // The number of executions previously will also conveniently be the index of this, the next execution
                    // (given that numbering starts at 0).
                    int currentNumExecutionsPreviously = jobInstance.getNumberOfExecutions();
                    jobExecution.setExecutionNumberForThisInstance(currentNumExecutionsPreviously);
                    jobInstance.setNumberOfExecutions(currentNumExecutionsPreviously + 1);

                    // Link in each direction
                    jobInstance.getJobExecutions().add(0, jobExecution);
                    jobExecution.setJobInstance(jobInstance);

                    entityMgr.persist(jobExecution);
                    return null;
                }
            }.runInNewOrExistingGlobalTran();

            validatePersistedJobExecution(jobExecution);

        } finally {
            em.close();
        }
        return jobExecution;
    }

    @Override
    public JobExecutionEntity getJobExecution(long jobExecutionId) throws NoSuchJobExecutionException {
        EntityManager em = getPsu().createEntityManager();
        try {
            JobExecutionEntity exec = em.find(JobExecutionEntity.class, jobExecutionId);
            if (exec == null) {
                throw new NoSuchJobExecutionException("No job execution found for id = " + jobExecutionId);
            }
            return exec;
        } finally {
            em.close();
        }
    }

    /**
     * @param jobInstanceId
     * @return The executions are ordered in sequence, from most-recent to least-recent.
     *         The container keeps its own order and does not depend on execution id or creation time to order these.
     */
    @Override
    public List<JobExecutionEntity> getJobExecutionsFromJobInstanceId(long jobInstanceId) throws NoSuchJobInstanceException {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<JobExecutionEntity> query = em.createNamedQuery(JobExecutionEntity.GET_JOB_EXECUTIONS_MOST_TO_LEAST_RECENT_BY_INSTANCE,
                                                                       JobExecutionEntity.class);
            query.setParameter("instanceId", jobInstanceId);
            List<JobExecutionEntity> result = query.getResultList();
            if (result == null || result.size() == 0) {
                // call this to trigger NoSuchJobInstanceException if instance is completely unknown (as opposed to there being no executions
                getJobInstance(jobInstanceId);
                if (result == null) {
                    return new ArrayList<JobExecutionEntity>();
                }
            }
            return result;
        } finally {
            em.close();
        }
    }

    @Override
    public List<Long> getJobExecutionsRunning(String jobName) {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<Long> query = em.createNamedQuery(JobExecutionEntity.GET_JOB_EXECUTIONIDS_BY_NAME_AND_STATUSES_QUERY,
                                                         Long.class);
            query.setParameter("name", jobName);
            query.setParameter("status", RUNNING_STATUSES);
            List<Long> result = query.getResultList();
            if (result == null) {
                return new ArrayList<Long>();
            }
            return result;
        } finally {
            em.close();
        }
    }

    /**
     * This method is called during recovery processing.
     *
     * Note: This is not a method on the persistence service, only on the JPA persistence impl
     *
     * @return List<JobExecutionEntity> of jobexecutions with a "running" status and this server's serverId
     */
    public List<JobExecutionEntity> getJobExecutionsRunningLocalToServer(PersistenceServiceUnit psu) {

        EntityManager em = psu.createEntityManager();
        try {
            TypedQuery<JobExecutionEntity> query = em.createNamedQuery(JobExecutionEntity.GET_JOB_EXECUTIONS_BY_SERVERID_AND_STATUSES_QUERY,
                                                                       JobExecutionEntity.class);
            query.setParameter("serverid", batchLocationService.getServerId());
            query.setParameter("status", RUNNING_STATUSES);
            List<JobExecutionEntity> result = query.getResultList();
            if (result == null) {
                return new ArrayList<JobExecutionEntity>();
            }
            return result;
        } finally {
            em.close();
        }
    }

    /**
     * This method is called during recovery processing.
     *
     * Note: This is not a method on the persistence service, only on the JPA persistence impl
     *
     * @return List<RemotablePartitionEntity> of partitions with a "running" status and this server's serverId
     */
    public List<RemotablePartitionEntity> getPartitionsRunningLocalToServer(PersistenceServiceUnit psu) {

        EntityManager em = psu.createEntityManager();
        try {
            TypedQuery<RemotablePartitionEntity> query = em.createNamedQuery(RemotablePartitionEntity.GET_PARTITION_STEP_THREAD_EXECUTIONIDS_BY_SERVERID_AND_STATUSES_QUERY,
                                                                             RemotablePartitionEntity.class);
            query.setParameter("serverid", batchLocationService.getServerId());
            query.setParameter("status", RUNNING_STATUSES);
            List<RemotablePartitionEntity> result = query.getResultList();
            if (result == null) {
                return new ArrayList<RemotablePartitionEntity>();
            }
            return result;
        } finally {
            em.close();
        }
    }

    @Override
    public JobExecutionEntity getJobExecutionFromJobExecNum(long jobInstanceId, int jobExecNum) throws NoSuchJobInstanceException, IllegalArgumentException {

        EntityManager em = getPsu().createEntityManager();
        TypedQuery<JobExecutionEntity> query = em.createNamedQuery(
                                                                   JobExecutionEntity.GET_JOB_EXECUTIONS_BY_JOB_INST_ID_AND_JOB_EXEC_NUM,
                                                                   JobExecutionEntity.class);

        query.setParameter("instanceId", jobInstanceId);
        query.setParameter("jobExecNum", jobExecNum);

        List<JobExecutionEntity> jobExec = query.getResultList();

        if (jobExec.size() > 1) {
            throw new IllegalStateException("Found more than one result for jobInstanceId: " + jobInstanceId + ", jobExecNum: " + jobExecNum);
        }

        if (jobExec == null || jobExec.size() == 0) {

            // call this to trigger NoSuchJobInstanceException if instance is completely unknown (as opposed to there being no executions
            getJobInstance(jobInstanceId);

            throw new IllegalArgumentException("Didn't find any job execution entries at job instance id: "
                                               + jobInstanceId + ", job execution number: "
                                               + jobExecNum);
        }

        return (jobExec.get(0));
    }

    @Override
    public JobExecutionEntity updateJobExecutionLogDir(final long jobExecutionId, final String logDirPath) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<JobExecutionEntity>(em) {
                @Override
                public JobExecutionEntity call() {
                    JobExecutionEntity exec = entityMgr.find(JobExecutionEntity.class, jobExecutionId);
                    if (exec == null) {
                        throw new NoSuchJobExecutionException("No job execution found for id = " + jobExecutionId);
                    }
                    exec.setLogpath(logDirPath);
                    return exec;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public JobExecutionEntity updateJobExecutionServerIdAndRestUrlForStartingJob(final long jobExecutionId) throws NoSuchJobExecutionException, JobStoppedException {
        EntityManager em = getPsu().createEntityManager();

        final TypedQuery<JobExecutionEntity> query = em.createNamedQuery(JobExecutionEntity.UPDATE_JOB_EXECUTION_SERVERID_AND_RESTURL_FOR_STARTING_JOB,
                                                                         JobExecutionEntity.class);

        query.setParameter("serverId", batchLocationService.getServerId());
        query.setParameter("restUrl", batchLocationService.getBatchRestUrl());
        query.setParameter("jobExecId", jobExecutionId);
        try {
            return new TranRequest<JobExecutionEntity>(em) {
                @Override
                public JobExecutionEntity call() throws JobStoppedException {
                    JobExecutionEntity execution = entityMgr.find(JobExecutionEntity.class, jobExecutionId, LockModeType.PESSIMISTIC_WRITE);
                    if (execution == null) {
                        throw new NoSuchJobExecutionException("No job execution found for id = " + jobExecutionId);
                    }

                    int count = query.executeUpdate();
                    if (count > 0) {
                        // Need to refresh to pick up changes made to the database
                        entityMgr.refresh(execution);
                    } else {
                        // We're guarding here for the case that the execution has been stopped
                        // by the time we reach this query
                        String msg = "No job execution found for id = " + jobExecutionId + " and status = STARTING";
                        throw new JobStoppedException(msg);
                    }
                    return execution;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    /**
     *
     * @param instanceKey
     * @return new step thread execution id
     */
    @Override
    public TopLevelStepExecutionEntity createTopLevelStepExecutionAndNewThreadInstance(final long jobExecutionId, final StepThreadInstanceKey instanceKey,
                                                                                       final boolean isPartitioned) {
        //TODO - should we move this inside the tran?
        EntityManager em = getPsu().createEntityManager();
        try {
            TopLevelStepExecutionEntity stepExecution = new TranRequest<TopLevelStepExecutionEntity>(em) {
                @Override
                public TopLevelStepExecutionEntity call() {

                    // 1. Find related objects
                    final JobInstanceEntity jobInstance = entityMgr.find(JobInstanceEntity.class, instanceKey.getJobInstance());
                    if (jobInstance == null) {
                        throw new IllegalStateException("Didn't find JobInstanceEntity associated with step thread key value: " + instanceKey.getJobInstance());
                    }
                    final JobExecutionEntity jobExecution = entityMgr.find(JobExecutionEntity.class, jobExecutionId);
                    if (jobExecution == null) {
                        throw new IllegalStateException("Didn't find JobExecutionEntity associated with value: " + jobExecutionId);
                    }

                    // 2. Construct and initalize new entity instances
                    //   Note some important initialization (e.g. batch status = STARTING and startcount = 1), is done in the constructors
                    final TopLevelStepInstanceEntity stepInstance = new TopLevelStepInstanceEntity(jobInstance, instanceKey.getStepName(), isPartitioned);
                    final TopLevelStepExecutionEntity stepExecution = new TopLevelStepExecutionEntity(jobExecution, instanceKey.getStepName(), isPartitioned);

                    // 3. Update the relationships that didn't get updated in constructors

                    // 3a. Reverting to the safety of known-working, trying to do this drags in some
                    // extra considerations, which we'll have to come back to later (and we can since it
                    // shouldn't affect the table structure).
                    stepInstance.setLatestStepThreadExecution(stepExecution);
//					jobInstance.getStepThreadInstances().add(stepInstance);
//					jobExecution.getStepThreadExecutions().add(stepExecution);

                    // 4. Persist
                    entityMgr.persist(stepExecution);
                    entityMgr.persist(stepInstance);
                    return stepExecution;
                }
            }.runInNewOrExistingGlobalTran();

            validatePersistedStepExecution(stepExecution);

            return stepExecution;

        } finally {
            em.close();
        }
    }

    @Override
    public RemotablePartitionEntity updateRemotablePartitionInternalState(final long jobExecId, final String stepName, final int partitionNum,
                                                                          final RemotablePartitionState internalStatus) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<RemotablePartitionEntity>(em) {
                @Override
                public RemotablePartitionEntity call() {

                    RemotablePartitionKey partitionKey = new RemotablePartitionKey(jobExecId, stepName, partitionNum);
                    RemotablePartitionEntity partition = entityMgr.find(RemotablePartitionEntity.class, partitionKey);

                    //For backward compatibility, this can be null
                    if (partition != null) {
                        //It can be null because if the partition dispatcher is older version, there won't be any remotable partition
                        partition.setRestUrl(batchLocationService.getBatchRestUrl());
                        partition.setServerId(batchLocationService.getServerId());
                        partition.setInternalStatus(internalStatus);
                        partition.setLastUpdated(new Date());
                    }
                    return partition;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public RemotablePartitionEntity createRemotablePartition(final long jobExecId, final String stepName, final int partitionNum, final RemotablePartitionState partitionState) {
        //TODO - should we move this inside the tran?
        EntityManager em = getPsu().createEntityManager();

        try {
            RemotablePartitionEntity remotablePartition = new TranRequest<RemotablePartitionEntity>(em) {
                @Override
                public RemotablePartitionEntity call() {

                    final JobExecutionEntity jobExecution = entityMgr.find(JobExecutionEntity.class, jobExecId);
                    if (jobExecution == null) {
                        throw new IllegalStateException("Didn't find JobExecutionEntity associated with value: " + jobExecId);
                    }

                    // 2. Construct and initialize new entity instances
                    final RemotablePartitionEntity remotablePartition = new RemotablePartitionEntity(jobExecution, stepName, partitionNum);
                    remotablePartition.setInternalStatus(partitionState);
                    remotablePartition.setLastUpdated(new Date());
                    entityMgr.persist(remotablePartition);
                    return remotablePartition;
                }
            }.runInNewOrExistingGlobalTran();

            return remotablePartition;

        } finally {
            em.close();
        }
    }

    @Override
    public StepThreadExecutionEntity createPartitionStepExecutionAndNewThreadInstance(final long jobExecutionId, final StepThreadInstanceKey instanceKey,
                                                                                      final boolean isRemoteDispatch) {
        //TODO - should we move this inside the tran?
        EntityManager em = getPsu().createEntityManager();

        try {
            StepThreadExecutionEntity stepExecution = new TranRequest<StepThreadExecutionEntity>(em) {
                @Override
                public StepThreadExecutionEntity call() {

                    // 1. Find related objects
                    final JobInstanceEntity jobInstance = entityMgr.find(JobInstanceEntity.class, instanceKey.getJobInstance());
                    if (jobInstance == null) {
                        throw new IllegalStateException("Didn't find JobInstanceEntity associated with step thread key value: " + instanceKey.getJobInstance());
                    }
                    final JobExecutionEntity jobExecution = entityMgr.find(JobExecutionEntity.class, jobExecutionId);
                    if (jobExecution == null) {
                        throw new IllegalStateException("Didn't find JobExecutionEntity associated with value: " + jobExecutionId);
                    }
                    TypedQuery<TopLevelStepExecutionEntity> query = entityMgr.createNamedQuery(TopLevelStepExecutionEntity.GET_TOP_LEVEL_STEP_EXECUTION_BY_JOB_EXEC_AND_STEP_NAME,
                                                                                               TopLevelStepExecutionEntity.class);
                    query.setParameter("jobExecId", jobExecutionId);
                    query.setParameter("stepName", instanceKey.getStepName());
                    // getSingleResult() validates that there is only one match
                    final TopLevelStepExecutionEntity topLevelStepExecution = query.getSingleResult();

                    // 2. Construct and initalize new entity instances
                    //   Note some important initialization (e.g. batch status = STARTING and startcount = 1), is done in the constructors
                    final StepThreadInstanceEntity stepInstance = new StepThreadInstanceEntity(jobInstance, instanceKey.getStepName(), instanceKey.getPartitionNumber());
                    final StepThreadExecutionEntity stepExecution = new StepThreadExecutionEntity(jobExecution, instanceKey.getStepName(), instanceKey.getPartitionNumber());

                    // 3. Update the relationships that didn't get updated in constructors

                    // 3a. Reverting to the safety of known-working, trying to do this drags in some
                    // extra considerations, which we'll have to come back to later (and we can since it
                    // shouldn't affect the table structure).
                    stepInstance.setLatestStepThreadExecution(stepExecution);

//					jobInstance.getStepThreadInstances().add(stepInstance);
//					jobExecution.getStepThreadExecutions().add(stepExecution);
                    stepExecution.setTopLevelStepExecution(topLevelStepExecution);
                    //topLevelStepExecution.getTopLevelAndPartitionStepExecutions().add(stepExecution);
                    /*
                     * 222050 - Backout 205106
                     * RemotablePartitionEntity remotablePartition = null;
                     * if (isRemoteDispatch) {
                     * RemotablePartitionKey remotablePartitionKey = new RemotablePartitionKey(jobExecution.getExecutionId(), instanceKey.getStepName(),
                     * instanceKey.getPartitionNumber());
                     * remotablePartition = entityMgr.find(RemotablePartitionEntity.class, remotablePartitionKey);
                     *
                     * //It can be null because if the partition dispatcher is older version, there won't be any remotable partition
                     * if (remotablePartition != null) {
                     * remotablePartition.setStepExecution(stepExecution);
                     * }
                     * }
                     */
                    // 4. Persist
                    entityMgr.persist(stepInstance);
                    entityMgr.persist(stepExecution);
                    /*
                     * 222050 - Backout 205106
                     * if (isRemoteDispatch && remotablePartition != null) {
                     * entityMgr.persist(remotablePartition);
                     * }
                     */
                    return stepExecution;
                }
            }.runInNewOrExistingGlobalTran();

            validatePersistedStepExecution(stepExecution);

            return stepExecution;

        } finally {
            em.close();
        }
    }

    /**
     *
     * Needs to:
     *
     * 1. create new stepthreadexec id, in STARTING state 2. increment step
     * instance start count (for top-level only) 3. copy over persistent
     * userdata to new step exec 4. point step thread instance to latest
     * execution
     *
     *
     * @param instanceKey
     * @return new step thread execution
     */
    @Override
    public TopLevelStepExecutionEntity createTopLevelStepExecutionOnRestartFromPreviousStepInstance(final long jobExecutionId,
                                                                                                    final TopLevelStepInstanceEntity stepInstance) throws NoSuchJobExecutionException {
        EntityManager em = getPsu().createEntityManager();
        try {
            TopLevelStepExecutionEntity stepExecution = new TranRequest<TopLevelStepExecutionEntity>(em) {
                @Override
                public TopLevelStepExecutionEntity call() {

                    // 1. Find related objects
                    JobExecutionEntity newJobExecution = getJobExecution(jobExecutionId);
                    StepThreadExecutionEntity lastStepExecution = stepInstance.getLatestStepThreadExecution();

                    // 2. Construct and initalize new entity instances
                    TopLevelStepExecutionEntity newStepExecution = new TopLevelStepExecutionEntity(newJobExecution, stepInstance.getStepName(), stepInstance.isPartitionedStep());
                    newStepExecution.setPersistentUserDataBytes(lastStepExecution.getPersistentUserDataBytes());
                    stepInstance.incrementStartCount();

                    // 3. Update the relationships that didn't get updated in constructors

                    // 3a. Reverting to the safety of known-working, trying to do this drags in some
                    // extra considerations, which we'll have to come back to later (and we can since it
                    // shouldn't affect the table structure).
                    stepInstance.setLatestStepThreadExecution(newStepExecution);
//					newJobExecution.getStepThreadExecutions().add(newStepExecution);

                    // 4. Persist (The order seems to matter unless I did something else wrong)
                    entityMgr.persist(newStepExecution);
                    entityMgr.merge(stepInstance);
                    return newStepExecution;
                }
            }.runInNewOrExistingGlobalTran();

            validatePersistedStepExecution(stepExecution);

            return stepExecution;

        } finally {
            em.close();
        }
    }

    /**
     *
     * Needs to:
     *
     * 1. create new stepthreadexec id, in STARTING state 2. copy over persistent
     * userdata to new step exec 3. point step thread instance to latest
     * execution
     *
     *
     * @param instanceKey
     * @return new step thread execution
     */
    @Override
    public StepThreadExecutionEntity createPartitionStepExecutionOnRestartFromPreviousStepInstance(final long jobExecutionId, final StepThreadInstanceEntity stepThreadInstance,
                                                                                                   final boolean isRemoteDispatch) throws NoSuchJobExecutionException {
        EntityManager em = getPsu().createEntityManager();
        try {
            StepThreadExecutionEntity stepExecution = new TranRequest<StepThreadExecutionEntity>(em) {
                @Override
                public StepThreadExecutionEntity call() {

                    // 1. Find related objects
                    JobExecutionEntity newJobExecution = getJobExecution(jobExecutionId);
                    StepThreadExecutionEntity lastStepExecution = stepThreadInstance.getLatestStepThreadExecution();

                    TypedQuery<TopLevelStepExecutionEntity> query = entityMgr.createNamedQuery(TopLevelStepExecutionEntity.GET_TOP_LEVEL_STEP_EXECUTION_BY_JOB_EXEC_AND_STEP_NAME,
                                                                                               TopLevelStepExecutionEntity.class);
                    query.setParameter("jobExecId", jobExecutionId);
                    query.setParameter("stepName", stepThreadInstance.getStepName());
                    // getSingleResult() validates that there is only one match
                    final TopLevelStepExecutionEntity topLevelStepExecution = query.getSingleResult();

                    // 2. Construct and initalize new entity instances
                    StepThreadExecutionEntity newStepExecution = new StepThreadExecutionEntity(newJobExecution, stepThreadInstance.getStepName(), stepThreadInstance.getPartitionNumber());
                    newStepExecution.setPersistentUserDataBytes(lastStepExecution.getPersistentUserDataBytes());

                    // 3. Update the relationships that didn't get updated in constructors

                    // 3a. Reverting to the safety of known-working, trying to do this drags in some
                    // extra considerations, which we'll have to come back to later (and we can since it
                    // shouldn't affect the table structure).
                    stepThreadInstance.setLatestStepThreadExecution(newStepExecution);
//					newJobExecution.getStepThreadExecutions().add(newStepExecution);
                    //topLevelStepExecution.getTopLevelAndPartitionStepExecutions().add(newStepExecution);
                    newStepExecution.setTopLevelStepExecution(topLevelStepExecution);
                    /*
                     * 222050 - Backout 205106
                     * RemotablePartitionEntity remotablePartition = null;
                     * if (isRemoteDispatch) {
                     * RemotablePartitionKey remotablePartitionKey = new RemotablePartitionKey(newJobExecution.getExecutionId(), stepThreadInstance.getStepName(),
                     * stepThreadInstance.getPartitionNumber());
                     * remotablePartition = entityMgr.find(RemotablePartitionEntity.class, remotablePartitionKey);
                     *
                     * //It can be null because if the partition dispatcher is older version, there won't be any remotable partition
                     * if (remotablePartition != null) {
                     * remotablePartition.setStepExecution(newStepExecution);
                     * }
                     * }
                     */
                    entityMgr.persist(newStepExecution);
                    entityMgr.merge(stepThreadInstance);
                    /*
                     * 222050 - Backout 205106
                     * if (isRemoteDispatch && remotablePartition != null) {
                     * entityMgr.persist(remotablePartition);
                     * }
                     */

                    return newStepExecution;
                }
            }.runInNewOrExistingGlobalTran();

            validatePersistedStepExecution(stepExecution);

            return stepExecution;

        } finally {
            em.close();
        }
    }

    /**
     *
     * Needs to:
     *
     * 1. create new stepthreadexec id, in STARTING state 2. increment step
     * instance start count (for top-level only, note this isn't the only imaginable spec interpreation..it's our own). You might consider refreshing the count
     * to 0 as well. * 3. don't copy persistent * userdata 4. delete checkpoint data 5. point step thread instance to latest
     * execution
     *
     *
     * @param instanceKey
     * @return new step thread execution id
     */
    @Override
    public TopLevelStepExecutionEntity createTopLevelStepExecutionOnRestartAndCleanStepInstance(final long jobExecutionId,
                                                                                                final TopLevelStepInstanceEntity stepInstance) throws NoSuchJobExecutionException {
        EntityManager em = getPsu().createEntityManager();
        try {
            TopLevelStepExecutionEntity stepExecution = new TranRequest<TopLevelStepExecutionEntity>(em) {
                @Override
                public TopLevelStepExecutionEntity call() {

                    // 1. Find related objects
                    final JobExecutionEntity newJobExecution = getJobExecution(jobExecutionId);

                    // 2. Construct and initalize new entity instances
                    TopLevelStepExecutionEntity newStepExecution = new TopLevelStepExecutionEntity(newJobExecution, stepInstance.getStepName(), stepInstance.isPartitionedStep());
                    stepInstance.incrementStartCount(); // Non-obvious interpretation of the spec
                    stepInstance.deleteCheckpointData();

                    // 3. Update the relationships that didn't get updated in constructors

                    // 3a. Reverting to the safety of known-working, trying to do this drags in some
                    // extra considerations, which we'll have to come back to later (and we can since it
                    // shouldn't affect the table structure).
                    stepInstance.setLatestStepThreadExecution(newStepExecution);
//					newJobExecution.getStepThreadExecutions().add(newStepExecution);

                    // 4. Persist (The order seems to matter unless I did something else wrong)
                    entityMgr.persist(newStepExecution);
                    entityMgr.merge(stepInstance);
                    return newStepExecution;
                }
            }.runInNewOrExistingGlobalTran();

            validatePersistedStepExecution(stepExecution);

            return stepExecution;

        } finally {
            em.close();
        }
    }

    /**
     * @return null if not found (don't throw exception)
     */
    @Override
    public StepThreadInstanceEntity getStepThreadInstance(StepThreadInstanceKey stepInstanceKey) {
        EntityManager em = getPsu().createEntityManager();
        try {
            StepThreadInstanceEntity instance = em.find(StepThreadInstanceEntity.class, stepInstanceKey);
            return instance;
        } finally {
            em.close();
        }
    }

    /**
     * TODO - should we validate that this really is a top-level key?
     *
     * @return list of partition numbers related to this top-level step instance
     *         which are in COMPLETED state, in order of increasing partition number.
     */
    @Override
    public List<Integer> getStepThreadInstancePartitionNumbersOfRelatedCompletedPartitions(
                                                                                           StepThreadInstanceKey topLevelKey) {

        EntityManager em = getPsu().createEntityManager();
        TypedQuery<Integer> query = em.createNamedQuery(TopLevelStepInstanceEntity.GET_RELATED_PARTITION_LEVEL_COMPLETED_PARTITION_NUMBERS,
                                                        Integer.class);
        query.setParameter("instanceId", topLevelKey.getJobInstance());
        query.setParameter("stepName", topLevelKey.getStepName());

        return query.getResultList();
    }

    @Override
    public StepThreadInstanceEntity updateStepThreadInstanceWithCheckpointData(final StepThreadInstanceEntity stepThreadInstance) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<StepThreadInstanceEntity>(em) {
                @Override
                public StepThreadInstanceEntity call() {
                    entityMgr.merge(stepThreadInstance);
                    return stepThreadInstance;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public TopLevelStepInstanceEntity updateStepThreadInstanceWithPartitionPlanSize(StepThreadInstanceKey stepInstanceKey, final int numCurrentPartitions) {
        EntityManager em = getPsu().createEntityManager();
        try {
            final TopLevelStepInstanceEntity stepInstance = em.find(TopLevelStepInstanceEntity.class, stepInstanceKey);
            if (stepInstance == null) {
                throw new IllegalStateException("No step thread instance found for key = " + stepInstanceKey);
            }
            return new TranRequest<TopLevelStepInstanceEntity>(em) {
                @Override
                public TopLevelStepInstanceEntity call() {
                    stepInstance.setPartitionPlanSize(numCurrentPartitions);
                    return stepInstance;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public void deleteStepThreadInstanceOfRelatedPartitions(final TopLevelStepInstanceKey instanceKey) {
        EntityManager em = getPsu().createEntityManager();
        try {
            new TranRequest<Void>(em) {
                @Override
                public Void call() {
                    TypedQuery<StepThreadInstanceEntity> query = entityMgr.createNamedQuery(TopLevelStepInstanceEntity.GET_RELATED_PARTITION_LEVEL_STEP_THREAD_INSTANCES,
                                                                                            StepThreadInstanceEntity.class);
                    query.setParameter("instanceId", instanceKey.getJobInstance());
                    query.setParameter("stepName", instanceKey.getStepName());
                    final List<StepThreadInstanceEntity> relatedPartitionInstances = query.getResultList();

                    for (StepThreadInstanceEntity partitionInstance : relatedPartitionInstances) {
                        entityMgr.remove(partitionInstance);
                    }
                    return null;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public StepThreadExecutionEntity getStepThreadExecution(long stepExecutionId) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return em.find(StepThreadExecutionEntity.class, stepExecutionId);
        } finally {
            em.close();
        }
    }

    /**
     * order by start time, ascending
     */
    @Override
    public List<StepExecution> getStepExecutionsTopLevelFromJobExecutionId(long jobExecutionId) throws NoSuchJobExecutionException {
        EntityManager em = getPsu().createEntityManager();
        try {
            TypedQuery<StepExecution> query = em.createNamedQuery(TopLevelStepExecutionEntity.GET_TOP_LEVEL_STEP_EXECUTIONS_BY_JOB_EXEC_SORT_BY_START_TIME_ASC,
                                                                  StepExecution.class);
            query.setParameter("jobExecId", jobExecutionId);
            List<StepExecution> result = query.getResultList();
            if (result == null) {
                result = new ArrayList<StepExecution>();
            }
            // If empty, try to get job execution to generate NoSuchJobExecutionException if unknown id
            if (result.isEmpty()) {
                getJobExecution(jobExecutionId);
            }
            return result;
        } finally {
            em.close();
        }
    }

    @Override
    public WSStepThreadExecutionAggregate getStepExecutionAggregateFromJobExecutionNumberAndStepName(long jobInstanceId, int jobExecNum,
                                                                                                     String stepName) throws NoSuchJobInstanceException, IllegalArgumentException {

        WSStepThreadExecutionAggregateImpl retVal = new WSStepThreadExecutionAggregateImpl();

        EntityManager em = getPsu().createEntityManager();
        // 222050 - Backout 205106
        // Query query = em.createNamedQuery(TopLevelStepExecutionEntity.GET_TOP_LEVEL_STEP_EXECUTION_BY_JOB_INSTANCE_JOB_EXEC_NUM_AND_STEP_NAME);
        TypedQuery<StepThreadExecutionEntity> query = em.createNamedQuery(TopLevelStepExecutionEntity.GET_TOP_LEVEL_STEP_EXECUTION_BY_JOB_INSTANCE_JOB_EXEC_NUM_AND_STEP_NAME,
                                                                          StepThreadExecutionEntity.class);

        query.setParameter("jobInstanceId", jobInstanceId);
        query.setParameter("jobExecNum", jobExecNum);
        query.setParameter("stepName", stepName);
        // 222050 - Backout 205106
        // List<Object[]> stepExecs = query.getResultList();
        List<StepThreadExecutionEntity> stepExecs = query.getResultList();

        if (stepExecs == null || stepExecs.size() == 0) {
            // Trigger NoSuchJobInstanceException
            getJobInstance(jobInstanceId);

            throw new IllegalArgumentException("Didn't find any step thread exec entries at job instance id: " + jobInstanceId + ", job execution number: " + jobExecNum
                                               + ", and stepName: " + stepName);
        }

        // Verify the first is the top-level.
        try {
            // 220050 - Backout 205106 TopLevelStepExecutionEntity topLevelStepExecution = (TopLevelStepExecutionEntity) stepExecs.get(0)[0];
            TopLevelStepExecutionEntity topLevelStepExecution = (TopLevelStepExecutionEntity) stepExecs.get(0);
            retVal.setTopLevelStepExecution(topLevelStepExecution);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Didn't find top-level step thread exec entry at job instance id: " + jobInstanceId + ", job execution number: " + jobExecNum
                                               + ", and stepName: " + stepName);
        }

        // Go through the list and store the entities properly
        /*
         * 222050 - Backout 205106
         * List<WSPartitionStepAggregate> partitionSteps = new ArrayList<WSPartitionStepAggregate>();
         * for (int i = 1; i < stepExecs.size(); i++) {
         * partitionSteps.add(new WSPartitionStepAggregateImpl(stepExecs.get(i)));
         * }
         *
         * retVal.setPartitionAggregate(partitionSteps);
         */
        retVal.setPartitionLevelStepExecutions(new ArrayList<WSPartitionStepThreadExecution>(stepExecs.subList(1, stepExecs.size())));
        return retVal;
    }

    @Override
    public StepThreadExecutionEntity updateStepExecution(final RuntimeStepExecution runtimeStepExecution) {
        EntityManager em = getPsu().createEntityManager();
        //Create a synchronization object
        TranSynchronization tranSynch = new TranSynchronization(runtimeStepExecution);
        try {
            Transaction tran = tranMgr.getTransaction();
            if (tran != null) {
                UOWCurrent uowCurrent = (UOWCurrent) tranMgr;
                tranMgr.registerSynchronization(uowCurrent.getUOWCoord(), tranSynch, EmbeddableWebSphereTransactionManager.SYNC_TIER_NORMAL);
            }
        } catch (Throwable t) {
            //TODO: nlsprops transform after verify working
            throw new IllegalStateException("TranSync messed up! Sync = " + tranSynch + " Exception: " + t.toString());
        }
        try {
            return new TranRequest<StepThreadExecutionEntity>(em) {
                @Override
                public StepThreadExecutionEntity call() {

                    StepThreadExecutionEntity stepExec = entityMgr.find(StepThreadExecutionEntity.class, runtimeStepExecution.getInternalStepThreadExecutionId());
                    if (stepExec == null) {
                        throw new IllegalStateException("StepThreadExecEntity with id =" + runtimeStepExecution.getInternalStepThreadExecutionId()
                                                        + " should be persisted at this point, but didn't find.");
                    }

                    updateStepExecutionStatusTimeStampsUserDataAndMetrics(stepExec, runtimeStepExecution);
                    return stepExec;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    /**
     * This method is called during recovery
     *
     * Set the lastUpdated for the given RemotablePartitionEntity
     */
    public RemotablePartitionEntity updateRemotablePartitionOnRecovery(PersistenceServiceUnit psu,
                                                                       final RemotablePartitionEntity partition) {
        // TODO Auto-generated method stub
        EntityManager em = psu.createEntityManager();
        try {
            return new TranRequest<RemotablePartitionEntity>(em) {
                @Override
                public RemotablePartitionEntity call() {
                    RemotablePartitionKey key = new RemotablePartitionKey(partition);
                    RemotablePartitionEntity remotablePartition = entityMgr.find(RemotablePartitionEntity.class, key);
                    remotablePartition.setLastUpdated(new Date());
                    return remotablePartition;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    /**
     * This method is called during recovery.
     *
     * Set the batchStatus, exitStatus, and endTime for the given stepExecution.
     */
    public StepThreadExecutionEntity updateStepExecutionOnRecovery(PersistenceServiceUnit psu,
                                                                   final long stepExecutionId,
                                                                   final BatchStatus newStepBatchStatus,
                                                                   final String newStepExitStatus,
                                                                   final Date endTime) throws IllegalArgumentException {

        EntityManager em = psu.createEntityManager();
        try {
            return new TranRequest<StepThreadExecutionEntity>(em) {
                @Override
                public StepThreadExecutionEntity call() {

                    StepThreadExecutionEntity stepExec = entityMgr.find(StepThreadExecutionEntity.class, stepExecutionId);
                    if (stepExec == null) {
                        throw new IllegalArgumentException("StepThreadExecEntity with id =" + stepExecutionId + " should be persisted at this point, but didn't find it.");
                    }

                    try {
                        verifyThreadStatusTransitionIsValid(stepExec, newStepBatchStatus);
                    } catch (BatchIllegalJobStatusTransitionException e) {
                        throw new PersistenceException(e);
                    }

                    stepExec.setBatchStatus(newStepBatchStatus);
                    stepExec.setExitStatus(newStepExitStatus);
                    stepExec.setEndTime(endTime);
                    return stepExec;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public TopLevelStepExecutionEntity updateStepExecutionWithPartitionAggregate(final RuntimeStepExecution runtimeStepExecution) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<TopLevelStepExecutionEntity>(em) {
                @Override
                public TopLevelStepExecutionEntity call() {
                    TopLevelStepExecutionEntity stepExec = entityMgr.find(TopLevelStepExecutionEntity.class, runtimeStepExecution.getInternalStepThreadExecutionId());
                    if (stepExec == null) {
                        throw new IllegalArgumentException("StepThreadExecEntity with id =" + runtimeStepExecution.getInternalStepThreadExecutionId()
                                                           + " should be persisted at this point, but didn't find.");
                    }
                    updateStepExecutionStatusTimeStampsUserDataAndMetrics(stepExec, runtimeStepExecution);
                    for (StepThreadExecutionEntity stepThreadExec : stepExec.getTopLevelAndPartitionStepExecutions()) {
                        // Exclude the one top-level entry, which shows up in this list, based on its type.
                        if (!(stepThreadExec instanceof TopLevelStepExecutionEntity)) {
                            stepExec.addMetrics(stepThreadExec);
                        }
                    }
                    return stepExec;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    @Override
    public WSStepThreadExecutionAggregate getStepExecutionAggregateFromJobExecutionId(
                                                                                      long jobExecutionId, String stepName) throws NoSuchJobExecutionException {
        WSStepThreadExecutionAggregateImpl retVal = new WSStepThreadExecutionAggregateImpl();

        EntityManager em = getPsu().createEntityManager();
        // 222050 - Backout 205106
        // Query query = em.createNamedQuery(TopLevelStepExecutionEntity.GET_ALL_RELATED_STEP_THREAD_EXECUTIONS_BY_JOB_EXEC_AND_STEP_NAME_SORT_BY_PART_NUM_ASC);
        TypedQuery<StepThreadExecutionEntity> query = em.createNamedQuery(TopLevelStepExecutionEntity.GET_ALL_RELATED_STEP_THREAD_EXECUTIONS_BY_JOB_EXEC_AND_STEP_NAME_SORT_BY_PART_NUM_ASC,
                                                                          StepThreadExecutionEntity.class);

        query.setParameter("jobExecId", jobExecutionId);
        query.setParameter("stepName", stepName);
        // 222050 - Backout 205106
        // List<Object[]> stepExecs = query.getResultList();
        List<StepThreadExecutionEntity> stepExecs = query.getResultList();

        if (stepExecs == null || stepExecs.size() == 0) {
            throw new IllegalArgumentException("Didn't find any step thread exec entries at job execution id: " + jobExecutionId + ", and stepName: " + stepName);
        }

        // Verify the first is the top-level.
        try {
            // 222050 - Backout 205106
            // TopLevelStepExecutionEntity topLevelStepExecution = (TopLevelStepExecutionEntity) stepExecs.get(0)[0];
            TopLevelStepExecutionEntity topLevelStepExecution = (TopLevelStepExecutionEntity) stepExecs.get(0);
            retVal.setTopLevelStepExecution(topLevelStepExecution);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Didn't find top-level step thread exec entry at job execution id: " + jobExecutionId + ", and stepName: " + stepName);
        }

        // Go through the list and store the entities properly
        /*
         * 222050 - Backout 205106
         * List<WSPartitionStepAggregate> partitionSteps = new ArrayList<WSPartitionStepAggregate>();
         * for (int i = 1; i < stepExecs.size(); i++) {
         * partitionSteps.add(new WSPartitionStepAggregateImpl(stepExecs.get(i)));
         * }
         *
         * retVal.setPartitionAggregate(partitionSteps);
         */
        retVal.setPartitionLevelStepExecutions(new ArrayList<WSPartitionStepThreadExecution>(stepExecs.subList(1, stepExecs.size())));
        return retVal;
    }

    @Override
    public WSStepThreadExecutionAggregate getStepExecutionAggregate(long topLevelStepExecutionId) throws IllegalArgumentException {

        WSStepThreadExecutionAggregateImpl retVal = new WSStepThreadExecutionAggregateImpl();

        EntityManager em = getPsu().createEntityManager();
        // 222050 - Backout 205106
        // Query query = em.createNamedQuery(TopLevelStepExecutionEntity.GET_ALL_RELATED_STEP_THREAD_EXECUTIONS_SORT_BY_PART_NUM_ASC);
        TypedQuery<StepThreadExecutionEntity> query = em.createNamedQuery(TopLevelStepExecutionEntity.GET_ALL_RELATED_STEP_THREAD_EXECUTIONS_SORT_BY_PART_NUM_ASC,
                                                                          StepThreadExecutionEntity.class);

        query.setParameter("topLevelStepExecutionId", topLevelStepExecutionId);
        // 222050 - Backout 205106
        // List<Object[]> stepExecs = query.getResultList();
        List<StepThreadExecutionEntity> stepExecs = query.getResultList();

        if (stepExecs == null || stepExecs.size() == 0) {
            throw new IllegalArgumentException("Didn't find any step thread exec entries at id: " + topLevelStepExecutionId);
        }

        // Verify the first is the top-level.
        try {
            // 222050 - Backout 205106
            // TopLevelStepExecutionEntity topLevelStepExecution = (TopLevelStepExecutionEntity) stepExecs.get(0)[0];
            TopLevelStepExecutionEntity topLevelStepExecution = (TopLevelStepExecutionEntity) stepExecs.get(0);
            retVal.setTopLevelStepExecution(topLevelStepExecution);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Didn't find top-level step thread exec entry at id: " + topLevelStepExecutionId, e);
        }

        // Go through the list and store the entities properly
        /*
         * 222050 - Backout 205106
         * List<WSPartitionStepAggregate> partitionSteps = new ArrayList<WSPartitionStepAggregate>();
         * for (int i = 1; i < stepExecs.size(); i++) {
         * partitionSteps.add(new WSPartitionStepAggregateImpl(stepExecs.get(i)));
         * }
         *
         * retVal.setPartitionAggregate(partitionSteps);
         */
        retVal.setPartitionLevelStepExecutions(new ArrayList<WSPartitionStepThreadExecution>(stepExecs.subList(1, stepExecs.size())));
        return retVal;
    }

    /**
     * This method is called during recovery.
     *
     * Note this method is not on the persistence interface, it is particular to the JPA persistence impl.
     *
     * Unlike other methods involving a list of the spec-defined instance and execution ids, this returns
     * a list sorted from low-to-high stepexecution id (rather than using a timestamp based ordering which
     * preserves order across a wraparound of ids when using a SEQUENCE).
     *
     * @return The list of StepExecutions with "running" statuses for the given jobExecutionId.
     */
    public List<StepExecution> getStepThreadExecutionsRunning(PersistenceServiceUnit psu, long jobExecutionId) {

        EntityManager em = psu.createEntityManager();
        try {
            TypedQuery<StepExecution> query = em.createNamedQuery(StepThreadExecutionEntity.GET_STEP_THREAD_EXECUTIONIDS_BY_JOB_EXEC_AND_STATUSES_QUERY,
                                                                  StepExecution.class);
            query.setParameter("jobExecutionId", jobExecutionId);
            query.setParameter("status", RUNNING_STATUSES);
            List<StepExecution> result = query.getResultList();
            if (result == null) {
                return new ArrayList<StepExecution>();
            }
            return result;
        } finally {
            em.close();
        }
    }

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// Disable the split-flow and partition execution methods for now.  Revisit when we do cross-JVM distribution of partitions and splitflows within
// a single job.   Rationale for not including this logic is we don't want to externalize new associated tables and create a customer DB migration
// problem if it turns out we need to make changes.
//
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.jbatch.container.services.IPersistenceManagerService#createSplitFlowExecution(com.ibm.jbatch.container.persistence.jpa.RemotableSplitFlowKey)
     */
//	@Override
//	public RemotableSplitFlowEntity createSplitFlowExecution(final RemotableSplitFlowKey splitFlowKey, final Date createTime) {
//
//		EntityManager em = getPsu().createEntityManager();
//		try {
//			return new TranRequest<RemotableSplitFlowEntity>(em){
//				public RemotableSplitFlowEntity call() {
//					final RemotableSplitFlowEntity splitFlow = new RemotableSplitFlowEntity();
//					splitFlow.setInternalStatus(BatchStatus.STARTING.ordinal());
//					splitFlow.setFlowName(splitFlowKey.getFlowName());
//					splitFlow.setCreateTime(createTime);
//					splitFlow.setServerId(batchLocationService.getServerId());
//					splitFlow.setRestUrl(batchLocationService.getBatchRestUrl());
//					JobExecutionEntity jobExec = entityMgr.find(JobExecutionEntity.class, splitFlowKey.getJobExec());
//					if (jobExec == null) {
//						throw new IllegalStateException("Didn't find JobExecutionEntity associated with value: " + splitFlowKey.getJobExec());
//					}
//					splitFlow.setJobExecution(jobExec);
//					jobExec.getSplitFlowExecutions().add(splitFlow);
//					entityMgr.persist(splitFlow);
//					return splitFlow;
//				}
//			}.runInNewOrExistingGlobalTran();
//		} finally {
//			em.close();
//		}
//	}
//
//	@Override
//	public RemotableSplitFlowEntity updateSplitFlowExecution(final RuntimeSplitFlowExecution runtimeSplitFlowExecution, final BatchStatus newBatchStatus, final Date date)
//		throws IllegalArgumentException {
//		EntityManager em = getPsu().createEntityManager();
//		try {
//			return new TranRequest<RemotableSplitFlowEntity>(em){
//				public RemotableSplitFlowEntity call() {
//					RemotableSplitFlowKey splitFlowKey = new RemotableSplitFlowKey(runtimeSplitFlowExecution.getTopLevelExecutionId(), runtimeSplitFlowExecution.getFlowName());
//					RemotableSplitFlowEntity splitFlowEntity = entityMgr.find(RemotableSplitFlowEntity.class, splitFlowKey);
//					if (splitFlowEntity == null) {
//						throw new IllegalArgumentException("No split flow execution found for key = " + splitFlowKey);
//					}
//					splitFlowEntity.setBatchStatus(newBatchStatus);
//					splitFlowEntity.setExitStatus(runtimeSplitFlowExecution.getExitStatus());
//					ExecutionStatus executionStatus = runtimeSplitFlowExecution.getFlowStatus();
//					if (executionStatus != null) {
//						splitFlowEntity.setInternalStatus(executionStatus.getExtendedBatchStatus().ordinal());
//					}
//					if (newBatchStatus.equals(BatchStatus.STARTED)) {
//						splitFlowEntity.setStartTime(date);
//					} else if (FINAL_STATUS_SET.contains(newBatchStatus)) {
//						splitFlowEntity.setEndTime(date);
//					}
//					return splitFlowEntity;
//				}
//			}.runInNewOrExistingGlobalTran();
//		} finally {
//			em.close();
//		}
//	}
//
//	@Override
//	public RemotableSplitFlowEntity updateSplitFlowExecutionLogDir(final RemotableSplitFlowKey key, final String logDirPath) {
//		EntityManager em = getPsu().createEntityManager();
//		try {
//			return new TranRequest<RemotableSplitFlowEntity>(em){
//				public RemotableSplitFlowEntity call() {
//					RemotableSplitFlowEntity splitFlowEntity = entityMgr.find(RemotableSplitFlowEntity.class, key);
//					if (splitFlowEntity == null) {
//						throw new IllegalArgumentException("No split flow execution found for key = " + key);
//					}
//					splitFlowEntity.setLogpath(logDirPath);
//					return splitFlowEntity;
//				}
//			}.runInNewOrExistingGlobalTran();
//		} finally {
//			em.close();
//		}
//	}
//
//	@Override
//	public RemotablePartitionEntity createPartitionExecution(final RemotablePartitionKey partitionKey, final Date createTime) {
//		EntityManager em = getPsu().createEntityManager();
//		try {
//			return new TranRequest<RemotablePartitionEntity>(em){
//				public RemotablePartitionEntity call() {
//					final RemotablePartitionEntity partition = new RemotablePartitionEntity();
//					partition.setStepName(partitionKey.getStepName());
//					partition.setPartitionNumber(partitionKey.getPartitionNumber());
//					partition.setInternalStatus(BatchStatus.STARTING.ordinal());
//					partition.setCreateTime(createTime);
//					partition.setServerId(batchLocationService.getServerId());
//					partition.setRestUrl(batchLocationService.getBatchRestUrl());
//					JobExecutionEntity jobExec = entityMgr.find(JobExecutionEntity.class, partitionKey.getJobExec());
//					if (jobExec == null) {
//						throw new IllegalStateException("Didn't find JobExecutionEntity associated with value: " + partitionKey.getJobExec());
//					}
//					partition.setJobExec(jobExec);
//					jobExec.getPartitionExecutions().add(partition);
//					entityMgr.persist(partition);
//					return partition;
//				}
//			}.runInNewOrExistingGlobalTran();
//		} finally {
//			em.close();
//		}
//	}
//
//	@Override
//	public RemotablePartitionEntity updatePartitionExecution(final RuntimePartitionExecution runtimePartitionExecution, final BatchStatus newBatchStatus, final Date date) {
//		EntityManager em = getPsu().createEntityManager();
//		try {
//			return new TranRequest<RemotablePartitionEntity>(em){
//				public RemotablePartitionEntity call() {
//					RemotablePartitionKey partitionKey = new RemotablePartitionKey(runtimePartitionExecution.getTopLevelExecutionId(),
//							runtimePartitionExecution.getStepName(), runtimePartitionExecution.getPartitionNumber());
//					RemotablePartitionEntity partitionEntity = entityMgr.find(RemotablePartitionEntity.class, partitionKey);
//					if (partitionEntity == null) {
//						throw new IllegalArgumentException("No partition execution found for key = " + partitionKey);
//					}
//					partitionEntity.setBatchStatus(newBatchStatus);
//					partitionEntity.setExitStatus(runtimePartitionExecution.getExitStatus());
//					partitionEntity.setInternalStatus(runtimePartitionExecution.getBatchStatus().ordinal());
//					if (newBatchStatus.equals(BatchStatus.STARTED)) {
//						partitionEntity.setStartTime(date);
//					} else if (FINAL_STATUS_SET.contains(newBatchStatus)) {
//						partitionEntity.setEndTime(date);
//					}
//					return partitionEntity;
//				}
//			}.runInNewOrExistingGlobalTran();
//		} finally {
//			em.close();
//		}
//
//	}
//
//	@Override
//	public RemotablePartitionEntity updatePartitionExecutionLogDir(final RemotablePartitionKey key, final String logDirPath) {
//		EntityManager em = getPsu().createEntityManager();
//		try {
//			return new TranRequest<RemotablePartitionEntity>(em){
//				public RemotablePartitionEntity call() {
//					RemotablePartitionEntity partitionEntity = entityMgr.find(RemotablePartitionEntity.class, key);
//					if (partitionEntity == null) {
//						throw new IllegalArgumentException("No partition execution found for key = " + key);
//					}
//					partitionEntity.setLogpath(logDirPath);
//					return partitionEntity;
//				}
//			}.runInNewOrExistingGlobalTran();
//		} finally {
//			em.close();
//		}
//
//	}

    @Override
    public void purgeInGlassfish(String submitter) {
        // TODO unused
    }

    @Override
    public boolean purgeJobInstanceAndRelatedData(long jobInstanceId) {
        EntityManager em = getPsu().createEntityManager();
        try {
            final JobInstanceEntity instance = em.find(JobInstanceEntity.class, jobInstanceId);
            if (instance == null) {
                throw new NoSuchJobInstanceException("No job instance found for id = " + jobInstanceId);
            }
            new TranRequest<Void>(em) {
                @Override
                public Void call() {
                    entityMgr.remove(instance);
                    return null;
                }
            }.runInNewOrExistingGlobalTran();

            return true;
        } finally {
            em.close();
        }
    }

//
//
//
//
//	@Override
//	public List<StepThreadExecutionEntity> getStepThreadExecutionsForJobExecutionUnsorted(long execid) throws NoSuchJobExecutionException {
//		ArrayList<StepThreadExecutionEntity> resultSteps = new ArrayList<StepThreadExecutionEntity>();
//		EntityManager em = getPsu().createEntityManager();
//		try {
//			JobExecutionEntity exec = em.find(JobExecutionEntity.class, execid);
//			if (exec == null) {
//				throw new NoSuchJobExecutionException("No job execution found for id = " + execid);
//			}
//			for (StepThreadExecutionEntity stepExec : exec.getTopLevelAndPartitionStepExecutions()) {
//				resultSteps.add(stepExec);
//			}
//			return resultSteps;
//		} finally {
//			em.close();
//		}
//	}

// TO COMPLETE MERGING FROM HARRY - probably can do this with one query now
//    @Override
//    public List<StepExecution> getStepExecutionsPartitionsForJobExecution(long execid, String stepName)
//            throws NoSuchJobExecutionException {
//
//        EntityManager em = getPsu().createEntityManager();
//        ArrayList<StepExecution> resultSteps = new ArrayList<StepExecution>();
//
//        try {
//
//            long jobInstanceId = getJobInstanceIdFromExecutionId(execid);
//
//            TypedQuery<JobInstanceEntity> query = em
//                    .createQuery(
//                            "SELECT i FROM JobInstanceEntity i WHERE i.jobName LIKE :names ORDER BY i.jobInstanceId",
//                            JobInstanceEntity.class);
//            //query.setParameter("names", buildPartitionLevelQuery(jobInstanceId, stepName));
//
//            List<JobInstanceEntity> instances = query.getResultList();
//
//            if (instances != null) {
//                for (JobInstanceEntity instance : instances) {
//                    if (instance.getJobExecutions().size() > 1)
//                        throw new IllegalStateException("Subjob has more than one execution.");
//                    for (JobExecutionEntity exec : instance.getJobExecutions()) {
//                        for (StepThreadExecutionEntity step : exec.getStepThreadExecutions()) {
//                            resultSteps.add(step);
//                        }
//                    }
//                }
//            }
//
//            return resultSteps;
//        } finally {
//            em.close();
//        }
//
//    }

    /**
     * Inner class for wrapping EntityManager persistence functions with transactions, if needed. Used
     * with handleRetry to manage rollbacks.
     */
    private abstract class TranRequest<T> {

        EntityManager entityMgr;
        boolean newTran = false;
        private LocalTransactionCoordinator suspendedLTC;

        public TranRequest(EntityManager em) {
            entityMgr = em;
        }

        public T runInNewOrExistingGlobalTran() {

            T retVal = null;

            try {
                beginOrJoinTran();

                /*
                 * Here is the part where we call to the individual method, just
                 * wanted to make this stand out a bit more visually with all the tran & exc handling.
                 */
                retVal = call();

            } catch (Throwable t) {
                rollbackIfNewTranWasStarted(t);
            }

            commitIfNewTranWasStarted();

            return retVal;
        }

        public abstract T call() throws Exception;

        /**
         * Begin a new transaction, if one isn't currently active (nested transactions not supported).
         */
        protected void beginOrJoinTran() throws SystemException, NotSupportedException {

            int tranStatus = tranMgr.getStatus();

            if (tranStatus == Status.STATUS_NO_TRANSACTION) {
                logger.fine("Suspending current LTC and beginning new transaction");
                suspendedLTC = localTranCurrent.suspend();
                tranMgr.begin();
                newTran = true;
            } else {
                if (tranMgr.getTransaction() == null) {
                    throw new IllegalStateException("Didn't find active transaction but tranStatus = " + tranStatus);
                } else {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine("Joining existing tran: " + tranMgr.getTransaction());
                    }
                }
            }

            entityMgr.joinTransaction();
        }

        protected void commitIfNewTranWasStarted() {
            if (newTran) {
                logger.fine("Committing new transaction we started.");
                try {
                    tranMgr.commit();
                } catch (Throwable t) {
                    throw new PersistenceException(t);
                } finally {
                    try {
                        resumeAnyExistingLTC();
                    } catch (Throwable t) {
                        throw new PersistenceException("Caught throwable on resume of previous LTC.  Might mask earlier throwable, so check logs.", t);
                    }
                }
            } else {
                logger.fine("Exiting without committing previously-active transaction.");
            }
        }

        protected void rollbackIfNewTranWasStarted(Throwable caughtThrowable) throws PersistenceException {
            if (newTran) {
                logger.fine("Rollback new transaction we started.");
                try {
                    tranMgr.rollback();
                } catch (Throwable t1) {
                    throw new PersistenceException("Caught throwable on rollback after previous throwable: " + caughtThrowable, t1);
                } finally {
                    try {
                        resumeAnyExistingLTC();
                    } catch (Throwable t2) {
                        throw new PersistenceException("Caught throwable on resume of previous LTC.  Original throwable: " + caughtThrowable, t2);
                    }
                }
            } else {
                logger.fine("We didn't start a new transaction so simply let the exception get thrown back.");
            }

            // If we haven't gotten a new exception to chain, throw back the original one passed in as a parameter
            throw new PersistenceException(caughtThrowable);
        }

        protected void resumeAnyExistingLTC() {
            if (suspendedLTC != null) {
                localTranCurrent.resume(suspendedLTC);
            }
        }

    };

    @Override
    public void generate(Writer out) throws Exception {
        PersistenceServiceUnit ddlGen = createLatestPsu();
        ddlGen.generateDDL(out);
        ddlGen.close();
    }

    @Override
    public String getDDLFileName() {
        /*
         * Because the batchContainer configuration is a singleton we
         * can just use the config displayID as the DDL Filename. The file extension
         * will be added automatically for us.
         */
        return databaseStoreDisplayId + "_batchPersistence";
    }

    @Override
    public JobInstance updateJobInstanceWithInstanceStateAndBatchStatus(
                                                                        final long jobInstanceId, final InstanceState state, final BatchStatus batchStatus,
                                                                        final Date lastUpdated) {
        EntityManager em = getPsu().createEntityManager();
        try {
            return new TranRequest<JobInstance>(em) {
                @Override
                public JobInstance call() {
                    JobInstanceEntity instance = entityMgr.find(JobInstanceEntity.class, jobInstanceId);
                    if (instance == null) {
                        throw new NoSuchJobInstanceException("No job instance found for id = " + jobInstanceId);
                    }

                    //Thinking a state check will be enough in this case.
                    try {
                        verifyStateTransitionIsValid(instance, state);
                    } catch (BatchIllegalJobStatusTransitionException e) {
                        throw new PersistenceException(e);
                    }

                    instance.setInstanceState(state);
                    instance.setBatchStatus(batchStatus);
                    instance.setLastUpdatedTime(lastUpdated);
                    return instance;
                }
            }.runInNewOrExistingGlobalTran();
        } finally {
            em.close();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws Exception
     **/
    @Override
    public int getJobExecutionTableVersion() throws Exception {
        return getJobExecutionTableVersion(getPsu());
    }

    @FFDCIgnore(javax.persistence.PersistenceException.class)
    private int getJobExecutionTableVersion(PersistenceServiceUnit psu) throws Exception {
        if (executionVersion != null)
            return executionVersion;

        EntityManager em = psu.createEntityManager();
        try {
            // Verify that JOBPARAMETER exists by running a query against it.
            String queryString = "SELECT COUNT(e.jobParameterElements) FROM JobExecutionEntityV2 e";
            TypedQuery<Long> query = em.createQuery(queryString, Long.class);
            query.getSingleResult();
            logger.fine("The JOBPARAMETER table exists, job execution table version = 2");
            executionVersion = 2;
            return executionVersion;
        } catch (javax.persistence.PersistenceException e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof SQLSyntaxErrorException &&
                    cause.getMessage() != null &&
                    cause.getMessage().contains("JOBPARAMETER")) {
                    // The table isn't there.
                    logger.fine("The JOBPARAMETER table does not exist, job execution table version = 1");
                    executionVersion = 1;
                    return executionVersion;
                }
                cause = cause.getCause();
            }
            logger.fine("Unexpected exception while checking job execution table version, re-throwing");
            throw e;
        } finally {
            em.close();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws Exception
     **/
    @Override
    public int getJobInstanceTableVersion() throws Exception {
        return getJobInstanceTableVersion(getPsu());
    }

    @FFDCIgnore(javax.persistence.PersistenceException.class)
    private int getJobInstanceTableVersion(PersistenceServiceUnit psu) throws Exception {
        if (instanceVersion != null)
            return instanceVersion;

        EntityManager em = psu.createEntityManager();
        try {
            // Verify that UPDATETIME column exists by running a query against it.
            String queryString = "SELECT COUNT(x.lastUpdatedTime) FROM JobInstanceEntityV2 x";
            TypedQuery<Long> query = em.createQuery(queryString, Long.class);
            query.getSingleResult();
            logger.fine("The UPDATETIME column exists, job instance table version = 2");
            instanceVersion = 2;
            return instanceVersion;
        } catch (javax.persistence.PersistenceException e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof SQLSyntaxErrorException &&
                    cause.getMessage() != null &&
                    cause.getMessage().contains("UPDATETIME")) {
                    // The column isn't there.
                    logger.fine("The UPDATETIME column does not exist, job instance table version = 1");
                    instanceVersion = 1;
                    return instanceVersion;
                }
                cause = cause.getCause();
            }
            logger.fine("Unexpected exception while checking job instance table version, re-throwing");
            throw e;
        } finally {
            em.close();
        }
    }
}
