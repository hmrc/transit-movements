#!/usr/bin/env bash
sbt clean scalafmtCheckAll coverage test IntegrationTest/test coverageOff coverageReport
