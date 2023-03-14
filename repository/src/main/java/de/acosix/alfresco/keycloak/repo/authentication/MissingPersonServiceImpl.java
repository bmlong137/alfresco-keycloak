package de.acosix.alfresco.keycloak.repo.authentication;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.lock.JobLockService;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.security.NoSuchPersonException;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MissingPersonServiceImpl {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(MissingPersonServiceImpl.class);
	
	private PersonService personService;
	
	private TransactionService txService;
	
	private JobLockService jobLockService;
	
	public void setPersonService(PersonService personService) {
		this.personService = personService;
	}
	
	public void setTransactionService(TransactionService txService) {
		this.txService = txService;
	}
	
	public void setJobLockService(JobLockService jobLockService) {
		this.jobLockService = jobLockService;
	}
	
	public void handleAuthentication(final String username, AuthenticationException ae) throws AuthenticationException {
		if (!ae.getMessage().contains("does not exist in Alfresco")) {
			LOGGER.debug("Not supported by MissingPersonService: " + ae.getMessage(), ae);
			throw ae;
		}
		
		LOGGER.debug("AuthenticationException; user doesn't exist; creating person: {}", username);
		this.createPerson(username);
	}
	
	public void handleNoSuchPerson(final String username, NoSuchPersonException nspe) throws NoSuchPersonException {
		LOGGER.debug("NoSuchPersonException; Creating person: {}", username);
		this.createPerson(username);
	}
	
	public void handle(String username, RuntimeException re) throws RuntimeException {
		if (re instanceof AuthenticationException) {
			this.handleAuthentication(username, (AuthenticationException) re);
		} else if (re instanceof NoSuchPersonException) {
			this.handleNoSuchPerson(username, (NoSuchPersonException) re);
		} else if (re.getCause() instanceof AuthenticationException) {
			this.handleAuthentication(username, (AuthenticationException) re.getCause());
		} else if (re.getCause() instanceof NoSuchPersonException) {
			this.handleNoSuchPerson(username, (NoSuchPersonException) re.getCause());
		} else {
			LOGGER.debug("Not supported by MissingPersonService: " + re.getMessage() + " => " + re.getCause(), re);
			throw re;
		}
	}
	
	private void createPerson(final String username) {
        final RetryingTransactionCallback<NodeRef> rtcallback = new RetryingTransactionCallback<NodeRef>() {
        	@Override
        	public NodeRef execute() {
				if (personService.personExists(username)) {
					LOGGER.debug("Person (now) already exists: {}", username);
					return null;
				} else {
					LOGGER.info("Creating person: {}", username);
					
					Map<QName, Serializable> properties = new HashMap<>();
					properties.put(ContentModel.PROP_USERNAME, username);
					properties.put(ContentModel.PROP_FIRSTNAME, "Keycloak");
					properties.put(ContentModel.PROP_LASTNAME, "Unknown");
					return personService.createPerson(properties);
				}
			}
		};
		
		try {
			AuthenticationUtil.runAsSystem(new RunAsWork<NodeRef>() {
				@Override
				public NodeRef doWork() {
					boolean readonly = txService.isReadOnly();
					QName lockQname = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, QName.createValidLocalName(username));

					LOGGER.trace("Obtaining exclusive lock on creation of person: {}", lockQname);
					String lockId = jobLockService.getLock(lockQname, 2000L, 250L, 20);
					try {
						return txService.getRetryingTransactionHelper().doInTransaction(rtcallback, false, readonly);
					} finally {
						LOGGER.trace("Releasing exclusive lock: {}", lockQname);
						jobLockService.releaseLock(lockId, lockQname);
					}
				}
			});
		} catch (RuntimeException re) {
			LOGGER.warn("Failed to create person: {} => {}", username, re.getMessage());
			if (re instanceof AlfrescoRuntimeException) {
				if (re.getMessage().contains("already exists"))
					LOGGER.info("Person already exists; likely thwarted race condition: {}", username);
					return;
			}
			
			throw re;
		}
	}

}
