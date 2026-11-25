#!/usr/bin/env python3
"""Reads and validates the demo config (dev/deployment/demo.example.yaml) for the deployment scripts.

  python3 dev/deployment/demo-config.py check        validate and print a summary (no secrets exist in it)
  python3 dev/deployment/demo-config.py get KEY      print one value: clubHost, idHost, adminEmail,
                                                     envDirectory, clubName or emailSender (empty if unset)

The file is OPENCLUB_DEMO_CONFIG, or /etc/openclub/demo.yaml. Unknown keys and invalid values are errors, so a
typo cannot silently fall back to a default.
"""
import os
import re
import sys

DEFAULT_PATH = "/etc/openclub/demo.yaml"
HOST = re.compile(r"^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
EMAIL = re.compile(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$")
PATH = re.compile(r"^/[A-Za-z0-9._/-]+$")
KEYS = {"clubHost", "idHost", "adminEmail", "envDirectory", "clubName", "email", "push"}


def fail(message):
    print(f"Demo config: {message}", file=sys.stderr)
    sys.exit(2)


def load(path):
    try:
        import yaml
    except ImportError:
        fail("PyYAML is missing; install it with: apt-get install -y python3-yaml")
    if not os.path.isfile(path):
        fail(f"{path} not found; copy dev/deployment/demo.example.yaml there and edit it")
    with open(path, encoding="utf-8") as handle:
        class UniqueKeyLoader(yaml.SafeLoader):
            """Rejects a setting written twice, where plain YAML would silently keep the last one."""

        def unique_mapping(loader, node, deep=False):
            keys = [loader.construct_object(key, deep=deep) for key, _ in node.value]
            duplicates = sorted({str(key) for key in keys if keys.count(key) > 1})
            if duplicates:
                fail(f"setting(s) written twice: {', '.join(duplicates)}")
            return loader.construct_mapping(node, deep=deep)

        UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)
        try:
            data = yaml.load(handle, Loader=UniqueKeyLoader)
        except yaml.YAMLError as error:
            fail(f"{path} is not valid YAML ({error.__class__.__name__})")
    if not isinstance(data, dict):
        fail(f"{path} must be a mapping of settings")
    unknown = sorted(set(map(str, data)) - KEYS)
    if unknown:
        fail(f"unknown setting(s): {', '.join(unknown)}")

    def text(key, default=None):
        value = data.get(key, default)
        if value is None:
            fail(f"{key} is required")
        if not isinstance(value, str):
            fail(f"{key} must be text")
        return value.strip()

    config = {
        "clubHost": text("clubHost"),
        "idHost": text("idHost"),
        "adminEmail": text("adminEmail"),
        "envDirectory": text("envDirectory", "/opt/openclub-demo-env"),
        "clubName": text("clubName", "Demo Club"),
        "emailSender": "",
        "pushHosts": "",
    }
    for key in ("clubHost", "idHost"):
        if not HOST.match(config[key]):
            fail(f"{key} must be a lowercase DNS name such as club.example.ch")
    if config["clubHost"] == config["idHost"]:
        fail("clubHost and idHost must differ")
    if not EMAIL.match(config["adminEmail"]):
        fail("adminEmail must be an email address")
    # Written unquoted into .env and used as a Compose bind-mount source: no spaces, $, # or quotes.
    if not PATH.match(config["envDirectory"]) or "/../" in config["envDirectory"] + "/":
        fail("envDirectory must be an absolute path of letters, digits, '.', '_', '-' and '/'")
    if not 1 <= len(config["clubName"]) <= 200 or "\n" in config["clubName"]:
        fail("clubName must be 1 to 200 characters on one line")
    email = data.get("email")
    if email is not None:
        if not isinstance(email, dict) or set(map(str, email)) != {"sender"}:
            fail("email must contain exactly: sender")
        sender = email["sender"]
        if not isinstance(sender, str) or not EMAIL.match(sender.strip()):
            fail("email.sender must be an email address")
        config["emailSender"] = sender.strip()
    push = data.get("push")
    if push is not None:
        if not isinstance(push, dict) or set(map(str, push)) != {"hosts"} or not isinstance(push["hosts"], list) or not push["hosts"]:
            fail("push must contain exactly: hosts (a list of push service host names)")
        hosts = []
        for host in push["hosts"]:
            name = str(host).strip().lower()
            if not HOST.match(name.lstrip(".")):
                fail("push.hosts must be host names such as fcm.googleapis.com or .push.apple.com")
            hosts.append(name)
        config["pushHosts"] = ",".join(hosts)
    return config


def main(argv):
    path = os.environ.get("OPENCLUB_DEMO_CONFIG") or DEFAULT_PATH
    if argv == ["check"]:
        config = load(path)
        print(f"{path}: app https://{config['clubHost']}, identity https://{config['idHost']}, "
              f"secrets in {config['envDirectory']}, club \"{config['clubName']}\", "
              f"email {'via ' + config['emailSender'] if config['emailSender'] else 'off'}, "
              f"push {'to ' + config['pushHosts'] if config['pushHosts'] else 'off'}")
    elif len(argv) == 2 and argv[0] == "get":
        config = load(path)
        if argv[1] not in config:
            fail(f"unknown key {argv[1]}")
        print(config[argv[1]])
    else:
        print(__doc__.strip(), file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main(sys.argv[1:])
