/*
 * Copyright (c) 2017 SAP and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * Contributors:
 * SAP - initial API and implementation
 */

package org.eclipse.dirigible.database.ds.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;

import javax.inject.Inject;
import javax.sql.DataSource;

import org.apache.commons.io.IOUtils;
import org.eclipse.dirigible.core.test.AbstractGuiceTest;
import org.eclipse.dirigible.database.ds.model.DataStructureDataDeleteModel;
import org.eclipse.dirigible.database.ds.model.DataStructureModelFactory;
import org.eclipse.dirigible.database.ds.synchronizer.DataStructuresSynchronizer;
import org.eclipse.dirigible.database.persistence.PersistenceManager;
import org.junit.Before;
import org.junit.Test;

/**
 * The Class DataStructureDataDeleteTest.
 */
public class DataStructureDataDeleteTest extends AbstractGuiceTest {

	/** The data structure core service. */
	@Inject
	private DataStructuresSynchronizer dataStructuresSynchronizer;

	/** The datasource */
	@Inject
	private DataSource dataSource;

	/**
	 * Sets the up.
	 *
	 * @throws Exception
	 *             the exception
	 */
	@Before
	public void setUp() throws Exception {
		this.dataStructuresSynchronizer = getInjector().getInstance(DataStructuresSynchronizer.class);
		this.dataSource = getInjector().getInstance(DataSource.class);
	}

	/**
	 * Delete data
	 */
	@Test
	public void deleteData() {
		try {
			InputStream in = DataStructureDataDeleteTest.class.getResourceAsStream("/orders.delete");
			try {
				String dataFile = IOUtils.toString(in, StandardCharsets.UTF_8);
				DataStructureDataDeleteModel data = DataStructureModelFactory.parseDelete("/orders.delete", dataFile);
				assertEquals("1|Order 1|11.11", data.getContent()); // only the first column matters anyway
				Connection connection = null;
				try {
					connection = dataSource.getConnection();

					PersistenceManager<Order> persistenceManager = new PersistenceManager<Order>();
					if (!persistenceManager.tableExists(connection, Order.class)) {
						persistenceManager.tableCreate(connection, Order.class);
					} else {
						persistenceManager.tableDrop(connection, Order.class);
						persistenceManager.tableCreate(connection, Order.class);
					}

					Order order = new Order();
					order.setId(1);
					order.setSubject("Subject 1");
					order.setAmount(54.54);
					persistenceManager.insert(connection, order);

					List<Order> orders = persistenceManager.query(connection, Order.class, "SELECT * FROM ORDERS");
					assertEquals(1, orders.size());
					Order orderFind = orders.get(0);

					assertEquals("Subject 1", orderFind.getSubject());

					dataStructuresSynchronizer.executeDeleteUpdate(data);

					Order orderDelete = persistenceManager.find(connection, Order.class, 1);

					assertNull(orderDelete);

					persistenceManager.tableDrop(connection, Order.class);
					boolean exists = persistenceManager.tableExists(connection, Order.class);
					assertFalse(exists);

				} finally {
					if (connection != null) {
						connection.close();
					}
				} 
			} finally {
				if (in != null) {
					in.close();
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

}
