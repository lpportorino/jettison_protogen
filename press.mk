# press.mk — the document press (tools/press): build self-contained HTML decks and paged
# documents from data, print them to PDF through headless Chromium, and check what was printed.
#
#   make -f press.mk image      build the tool image (once per Dockerfile or pins change)
#   make -f press.mk selftest   the both-directions proof: every planted defect fires on the
#                               bad fixtures and the good fixtures report nothing
#
# Both need the host docker CLI. The image tag is DERIVED from the Dockerfile and the lock
# files under pins/ — tools/press/bin/press derives the same tag — so the tag always names
# the image built from THIS Dockerfile and these pins.
PRESS_DIR := tools/press
PRESS_IMAGE := $(shell $(PRESS_DIR)/bin/image-tag)
ifeq ($(strip $(PRESS_IMAGE)),)
$(error press image tag could not be derived)
endif

.PHONY: image selftest
image:
	@docker image inspect $(PRESS_IMAGE) >/dev/null 2>&1 || docker build -t $(PRESS_IMAGE) $(PRESS_DIR)

selftest: image
	@PRESS_IMAGE=$(PRESS_IMAGE) $(PRESS_DIR)/bin/press selftest
