/*
 * Copyright (c) 2022 SAP SE or an SAP affiliate company and Eclipse Dirigible contributors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-FileCopyrightText: 2022 SAP SE or an SAP affiliate company and Eclipse Dirigible contributors
 * SPDX-License-Identifier: EPL-2.0
 */
const configurationsView = angular.module('configurations', ['ideUI', 'ideView']);

configurationsView.config(["messageHubProvider", function (messageHubProvider) {
    messageHubProvider.eventIdPrefix = 'configurations-view';
}]);

configurationsView.controller('ConfigurationsController', ['$scope', '$http', function ($scope, $http) {

    $http.get('/services/v4/core/configurations').then(function (response) {
        $scope.configurations = response.data;
    });

}]);