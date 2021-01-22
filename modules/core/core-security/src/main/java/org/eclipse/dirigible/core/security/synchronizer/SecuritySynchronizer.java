/*
 * Copyright (c) 2010-2021 SAP SE or an SAP affiliate company and Eclipse Dirigible contributors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-FileCopyrightText: 2010-2021 SAP SE or an SAP affiliate company and Eclipse Dirigible contributors
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.dirigible.core.security.synchronizer;

import static java.text.MessageFormat.format;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sql.DataSource;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.dirigible.commons.api.module.StaticInjector;
import org.eclipse.dirigible.core.scheduler.api.AbstractSynchronizer;
import org.eclipse.dirigible.core.scheduler.api.SchedulerException;
import org.eclipse.dirigible.core.scheduler.api.SynchronizationException;
import org.eclipse.dirigible.core.security.api.AccessException;
import org.eclipse.dirigible.core.security.api.ISecurityCoreService;
import org.eclipse.dirigible.core.security.definition.AccessDefinition;
import org.eclipse.dirigible.core.security.definition.RoleDefinition;
import org.eclipse.dirigible.core.security.service.SecurityCoreService;
import org.eclipse.dirigible.database.persistence.PersistenceManager;
import org.eclipse.dirigible.repository.api.IResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Security Synchronizer.
 */
@Singleton
public class SecuritySynchronizer extends AbstractSynchronizer {

	private static final Logger logger = LoggerFactory.getLogger(SecuritySynchronizer.class);

	private static final Map<String, RoleDefinition[]> ROLES_PREDELIVERED = Collections.synchronizedMap(new HashMap<String, RoleDefinition[]>());

	private static final Map<String, List<AccessDefinition>> ACCESS_PREDELIVERED = Collections
			.synchronizedMap(new HashMap<String, List<AccessDefinition>>());

	private static final Set<String> ROLES_SYNCHRONIZED = Collections.synchronizedSet(new HashSet<String>());

	private static final Set<String> ACCESS_SYNCHRONIZED = Collections.synchronizedSet(new HashSet<String>());

	@Inject
	private SecurityCoreService securityCoreService;
	
	@Inject
	private DataSource dataSource;

	@Inject
	private PersistenceManager<AccessDefinition> accessPersistenceManager;

	private volatile boolean upgradePassed; 
	
	private final String SYNCHRONIZER_NAME = this.getClass().getCanonicalName();

	/**
	 * Force synchronization.
	 */
	public static final void forceSynchronization() {
		SecuritySynchronizer securitySynchronizer = StaticInjector.getInjector().getInstance(SecuritySynchronizer.class);
		securitySynchronizer.synchronize();
	}

	/**
	 * Register pre-delivered roles.
	 *
	 * @param rolesPath
	 *            the roles path
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public void registerPredeliveredRoles(String rolesPath) throws IOException {
		InputStream in = SecuritySynchronizer.class.getResourceAsStream(rolesPath);
		try {
			String json = IOUtils.toString(in, StandardCharsets.UTF_8);
			RoleDefinition[] roleDefinitions = securityCoreService.parseRoles(json);
			for (RoleDefinition roleDefinition : roleDefinitions) {
				roleDefinition.setLocation(rolesPath);
			}
			ROLES_PREDELIVERED.put(rolesPath, roleDefinitions);
		} finally {
			if (in != null) {
				in.close();
			}
		}
	}

	/**
	 * Register pre-delivered access.
	 *
	 * @param accessPath
	 *            the access path
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public void registerPredeliveredAccess(String accessPath) throws IOException {
		InputStream in = SecuritySynchronizer.class.getResourceAsStream(accessPath);
		try {
			String json = IOUtils.toString(in, StandardCharsets.UTF_8);
			List<AccessDefinition> accessDefinitions = securityCoreService.parseAccessDefinitions(json);
			for (AccessDefinition accessDefinition : accessDefinitions) {
				accessDefinition.setLocation(accessPath);
			}
			ACCESS_PREDELIVERED.put(accessPath, accessDefinitions);
		} finally {
			if (in != null) {
				in.close();
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.dirigible.core.scheduler.api.ISynchronizer#synchronize()
	 */
	@Override
	public void synchronize() {
		synchronized (SecuritySynchronizer.class) {
			logger.trace("Synchronizing Roles and Access artifacts...");
			try {
				startSynchronization(SYNCHRONIZER_NAME);
				if (!upgradePassed) {
					upgradePassed = checkUpgrade();
				}
				clearCache();
				synchronizePredelivered();
				synchronizeRegistry();
				int immutableRolesCount = ROLES_PREDELIVERED.size();
				int immutableAccessCount = ACCESS_PREDELIVERED.size();
				int mutableRolesCount = ROLES_SYNCHRONIZED.size();
				int mutableAccessCount = ACCESS_SYNCHRONIZED.size();
				cleanup();
				clearCache();
				successfulSynchronization(SYNCHRONIZER_NAME, format("Immutable Roles: {0}, Immutable Accesses: {1}, Mutable Roles: {2}, Mutable Accesses: {3}", 
						immutableRolesCount, immutableAccessCount, mutableRolesCount, mutableAccessCount));
			} catch (Exception e) {
				logger.error("Synchronizing process for Roles and Access artifacts failed.", e);
				try {
					failedSynchronization(SYNCHRONIZER_NAME, e.getMessage());
				} catch (SchedulerException e1) {
					logger.error("Synchronizing process for Roles and Access files failed in registering the state log.", e);
				}
			}
			logger.trace("Done synchronizing Roles and Access artifacts.");
		}
	}

	private boolean checkUpgrade() throws SQLException {
		// from 3.1.x to 3.2.x
		try (Connection connection = dataSource.getConnection()) {
			Statement stmt = connection.createStatement();
			ResultSet rs;
			try {
				rs = stmt.executeQuery("SELECT * FROM DIRIGIBLE_SECURITY_ACCESS");
			} catch (Exception e) {
				logger.warn(e.getMessage());
				return false;
			}
			ResultSetMetaData rsmd = rs.getMetaData();
			int columnCount = rsmd.getColumnCount();
			
			List<String> columnNames = new ArrayList<>();
			for (int i = 1; i <= columnCount; i++ ) {
			  String name = rsmd.getColumnName(i);
			  columnNames.add(name);
			  if ("ACCESS_URI".equals(name)) {
				  logger.warn("Upgrading Security Access Synchronizer from 3.1.x version to 3.2.x ...");
				  accessPersistenceManager.tableDrop(connection, AccessDefinition.class);
				  logger.warn("Upgrade of Security Access Synchronizer from 3.1.x version to 3.2.x passed successfully.");
			  }
			}
			if (!columnNames.contains("ACCESS_HASH")) {
				logger.warn("Upgrading Security Access Synchronizer from 3.2.1 version to 3.2.2 ...");
				accessPersistenceManager.tableDrop(connection, AccessDefinition.class);
				logger.warn("Upgrade of Security Access Synchronizer from 3.2.1 version to 3.2.2 passed successfully.");
			}
		}
		return true;
	}

	/**
	 * Clear cache.
	 */
	private void clearCache() {
		ROLES_SYNCHRONIZED.clear();
		ACCESS_SYNCHRONIZED.clear();
		securityCoreService.clearCache();
	}

	/**
	 * Synchronize predelivered.
	 *
	 * @throws SynchronizationException
	 *             the synchronization exception
	 */
	private void synchronizePredelivered() throws SynchronizationException {
		logger.trace("Synchronizing predelivered Roles and Access artifacts...");

		// Roles
		for (RoleDefinition[] roleDefinitions : ROLES_PREDELIVERED.values()) {
			for (RoleDefinition roleDefinition : roleDefinitions) {
				synchronizeRole(roleDefinition);
			}
		}

		// Access
		for (List<AccessDefinition> accessDefinitions : ACCESS_PREDELIVERED.values()) {
			for (AccessDefinition accessDefinition : accessDefinitions) {
				accessDefinition.setHash("" + accessDefinition.hashCode());
				synchronizeAccess(accessDefinition);
			}
		}

		logger.trace("Done synchronizing predelivered Roles and Access artifacts.");
	}

	/**
	 * Synchronize role.
	 *
	 * @param roleDefinition
	 *            the role definition
	 * @throws SynchronizationException
	 *             the synchronization exception
	 */
	private void synchronizeRole(RoleDefinition roleDefinition) throws SynchronizationException {
		try {
			if (!securityCoreService.existsRole(roleDefinition.getName())) {
				securityCoreService.createRole(roleDefinition.getName(), roleDefinition.getLocation(), roleDefinition.getDescription());
				logger.info("Synchronized a new Role [{}] from location: {}", roleDefinition.getName(), roleDefinition.getLocation());
			} else {
				RoleDefinition existing = securityCoreService.getRole(roleDefinition.getName());
				if (!roleDefinition.equals(existing)) {
					if (!roleDefinition.getLocation().equals(existing.getLocation())) {
						throw new SynchronizationException(
								format("Trying to update the Role [{0}] already set from location [{1}] with a location [{2}]",
										roleDefinition.getName(), existing.getLocation(), roleDefinition.getLocation()));
					}
					securityCoreService.updateRole(roleDefinition.getName(), roleDefinition.getLocation(), roleDefinition.getDescription());
					logger.info("Synchronized a modified Role [{}] from location: {}", roleDefinition.getName(), roleDefinition.getLocation());
				}
			}
			ACCESS_SYNCHRONIZED.add(roleDefinition.getLocation());
		} catch (AccessException e) {
			throw new SynchronizationException(e);
		}
	}

	/**
	 * Synchronize access.
	 *
	 * @param accessDefinition
	 *            the access definition
	 * @throws SynchronizationException
	 *             the synchronization exception
	 */
	private void synchronizeAccess(AccessDefinition accessDefinition) throws SynchronizationException {
		try {
			if (!securityCoreService.existsAccessDefinition(accessDefinition.getScope(), accessDefinition.getPath(), accessDefinition.getMethod(), accessDefinition.getRole())) {
				securityCoreService.createAccessDefinition(accessDefinition.getLocation(), accessDefinition.getScope(), accessDefinition.getPath(), accessDefinition.getMethod(),
						accessDefinition.getRole(), accessDefinition.getDescription(), accessDefinition.getHash());
				logger.info("Synchronized a new Access definition [[{}]-[{}]-[{}]] from location: {}", accessDefinition.getPath(),
						accessDefinition.getMethod(), accessDefinition.getRole(), accessDefinition.getLocation());
			} else {
				AccessDefinition existing = securityCoreService.getAccessDefinition(accessDefinition.getScope(), accessDefinition.getPath(), accessDefinition.getMethod(),
						accessDefinition.getRole());
				if (!accessDefinition.equals(existing)) {
					if (!accessDefinition.getLocation().equals(existing.getLocation())) {
						throw new SynchronizationException(
								format("Trying to update the Access definition for [{0}-{1}-{2}] already set from location [{3}] with a location [{4}]",
										accessDefinition.getPath(), accessDefinition.getMethod(), accessDefinition.getRole(), existing.getLocation(),
										accessDefinition.getLocation()));
					}
					securityCoreService.updateAccessDefinition(existing.getId(), accessDefinition.getLocation(), accessDefinition.getScope(), accessDefinition.getPath(),
							accessDefinition.getMethod(), accessDefinition.getRole(), accessDefinition.getDescription(), accessDefinition.getHash());
					logger.info("Synchronized a modified Access definition [[{}]-[{}]-[{}]] from location: {}", accessDefinition.getPath(),
							accessDefinition.getMethod(), accessDefinition.getRole(), accessDefinition.getLocation());
				}
			}
			ACCESS_SYNCHRONIZED.add(accessDefinition.getLocation());
		} catch (AccessException e) {
			throw new SynchronizationException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.dirigible.core.scheduler.api.AbstractSynchronizer#synchronizeRegistry()
	 */
	@Override
	protected void synchronizeRegistry() throws SynchronizationException {
		logger.trace("Synchronizing Extension Points and Extensions from Registry...");

		super.synchronizeRegistry();

		logger.trace("Done synchronizing Extension Points and Extensions from Registry.");
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.dirigible.core.scheduler.api.AbstractSynchronizer#synchronizeResource(org.eclipse.dirigible.
	 * repository.api.IResource)
	 */
	@Override
	protected void synchronizeResource(IResource resource) throws SynchronizationException {
		String resourceName = resource.getName();
		if (resourceName.endsWith(ISecurityCoreService.FILE_EXTENSION_ROLES)) {
			RoleDefinition[] roleDefinitions = securityCoreService.parseRoles(resource.getContent());
			for (RoleDefinition roleDefinition : roleDefinitions) {
				roleDefinition.setLocation(getRegistryPath(resource));
				synchronizeRole(roleDefinition);
			}

		}
		if (resourceName.endsWith(ISecurityCoreService.FILE_EXTENSION_ACCESS)) {
			List<AccessDefinition> accessDefinitions = securityCoreService.parseAccessDefinitions(resource.getContent());
			String hash = DigestUtils.md5Hex(resource.getContent());
			try {
				securityCoreService.dropModifiedAccessDefinitions(getRegistryPath(resource), hash);
			} catch (AccessException e) {
				logger.error("Error deleting the modified Access Definitions", e);
			}
			
			for (AccessDefinition accessDefinition : accessDefinitions) {
				accessDefinition.setLocation(getRegistryPath(resource));
				accessDefinition.setHash(hash);
				synchronizeAccess(accessDefinition);
			}

		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.dirigible.core.scheduler.api.AbstractSynchronizer#cleanup()
	 */
	@Override
	protected void cleanup() throws SynchronizationException {
		logger.trace("Cleaning up Roles and Access artifacts...");
		super.cleanup();

		try {
			List<RoleDefinition> roleDefinitions = securityCoreService.getRoles();
			for (RoleDefinition roleDefinition : roleDefinitions) {
				if (!ACCESS_SYNCHRONIZED.contains(roleDefinition.getLocation())) {
					securityCoreService.removeRole(roleDefinition.getName());
					logger.warn("Cleaned up Role [{}] from location: {}", roleDefinition.getName(), roleDefinition.getLocation());
				}
			}

			List<AccessDefinition> accessDefinitions = securityCoreService.getAccessDefinitions();
			for (AccessDefinition accessDefinition : accessDefinitions) {
				if (!ACCESS_SYNCHRONIZED.contains(accessDefinition.getLocation())) {
					securityCoreService.removeAccessDefinition(accessDefinition.getId());
					logger.warn("Cleaned up Access definition [[{}]-[{}]-[{}]] from location: {}", accessDefinition.getPath(),
							accessDefinition.getMethod(), accessDefinition.getRole(), accessDefinition.getLocation());
				}
			}
		} catch (AccessException e) {
			throw new SynchronizationException(e);
		}

		logger.trace("Done cleaning up Roles and Access artifacts.");
	}
}
