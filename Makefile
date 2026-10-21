.PHONY: build test bank assistant eval up

build:          ## compile, test and package both services
	mvn -B -ntp verify

test:
	mvn -B -ntp test

bank:           ## mock core banking on :8001
	java -jar mock-bank/target/mock-bank-exec.jar

assistant:      ## assistant API + chat UI on :8000 (reads .env)
	java -jar assistant/target/assistant.jar

eval:           ## real-model scenario evals against a running assistant
	EVAL_BASE_URL=http://localhost:8000 mvn -B -ntp -q test -pl assistant -am -Dtest=ScenarioEval -Dsurefire.failIfNoSpecifiedTests=false

up:
	docker compose up --build
