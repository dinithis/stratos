# --------------------------------------------------------------
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# --------------------------------------------------------------
# Executes the deployment by pushing all necessary configurations and patches

define autoscaler::deploy ($service, $security, $target, $owner, $group) {
  file { "/tmp/${autoscaler::deployment_code}":
    ensure       => present,
    owner        => $owner,
    group        => $group,
    sourceselect => all,
    ignore       => '.svn',
    recurse      => true,
    source       => [
      "puppet:///modules/${service}/configs/",
      "puppet:///modules/${service}/patches/"]
  }

  exec {
    "Copy_${name}_modules_to_carbon_home":
      path    => '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/java/bin/',
      command => "cp -r /tmp/${autoscaler::deployment_code}/* ${target}/; chown -R ${owner}:${owner} ${target}/; chmod -R 755 ${target}/",
      require => File["/tmp/${autoscaler::deployment_code}"];

    "Remove_${name}_temporory_modules_directory":
      path    => '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/java/bin/',
      command => "rm -rf /tmp/${autoscaler::deployment_code}",
      require => Exec["Copy_${name}_modules_to_carbon_home"];
  }
}
