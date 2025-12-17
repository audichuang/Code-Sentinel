# Code Sentinel - Build Automation
# Optimized for minimal output and maximum code quality

.PHONY: check build verify clean test help dist run

# Default target
.DEFAULT_GOAL := help

# Gradle base command
GRADLE := ./gradlew --no-daemon --console=plain

# ==================== Main Commands ====================

## Full quality check: build + verify plugin compatibility
check:
	@printf "\033[0;33mRunning full quality check...\033[0m\n"
	@$(MAKE) -s build && $(MAKE) -s verify

## Build the plugin
build:
	@printf "Building plugin... "
	@if $(GRADLE) build -q 2>/dev/null; then \
		printf "\033[0;32mOK\033[0m\n"; \
	else \
		printf "\033[0;31mFAILED\033[0m\n"; \
		$(GRADLE) build 2>&1 | grep -iE "(error:|failed|exception)" | head -20; \
		exit 1; \
	fi

## Verify plugin compatibility with IntelliJ versions
verify:
	@printf "Verifying plugin compatibility... "
	@OUTPUT=$$($(GRADLE) verifyPlugin 2>&1); \
	RESULT=$$?; \
	if [ $$RESULT -eq 0 ]; then \
		COMPAT=$$(echo "$$OUTPUT" | grep -c "Compatible" || echo "0"); \
		printf "\033[0;32mOK\033[0m ($$COMPAT IDE versions compatible)\n"; \
		echo "$$OUTPUT" | grep -E "^Plugin .+: Compatible$$" | sed 's/^Plugin /  /; s/ against /: /' | head -5; \
	else \
		printf "\033[0;31mFAILED\033[0m\n"; \
		echo "$$OUTPUT" | grep -iE "(error|failed|incompatible|deprecated|scheduled for removal)" | head -30; \
		exit 1; \
	fi

## Run tests
test:
	@printf "Running tests... "
	@if $(GRADLE) test -q 2>/dev/null; then \
		printf "\033[0;32mOK\033[0m\n"; \
	else \
		printf "\033[0;31mFAILED\033[0m\n"; \
		$(GRADLE) test 2>&1 | grep -iE "(failed|failure|error:)" | head -20; \
		exit 1; \
	fi

## Clean build artifacts
clean:
	@printf "Cleaning... "
	@$(GRADLE) clean -q 2>/dev/null && printf "\033[0;32mOK\033[0m\n"

## Build distributable plugin zip
dist:
	@printf "Building distribution... "
	@if $(GRADLE) buildPlugin -q 2>/dev/null; then \
		printf "\033[0;32mOK\033[0m\n"; \
		ls -lh build/distributions/*.zip 2>/dev/null | awk '{print "  " $$NF " (" $$5 ")"}'; \
	else \
		printf "\033[0;31mFAILED\033[0m\n"; \
		exit 1; \
	fi

## Run IDE for testing
run:
	@printf "\033[0;33mStarting IDE...\033[0m\n"
	@$(GRADLE) runIde

# ==================== Help ====================

## Show this help
help:
	@echo "Code Sentinel - Build Commands"
	@echo ""
	@echo "Usage: make [target]"
	@echo ""
	@echo "Targets:"
	@echo "  check   - Full quality check (build + verify)"
	@echo "  build   - Build the plugin"
	@echo "  verify  - Verify plugin compatibility"
	@echo "  test    - Run tests"
	@echo "  clean   - Clean build artifacts"
	@echo "  dist    - Build distributable zip"
	@echo "  run     - Run IDE for testing"
	@echo "  help    - Show this help"
