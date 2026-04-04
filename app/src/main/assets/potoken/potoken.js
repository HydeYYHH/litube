(function (global) {
  if (global.__litePoToken) {
    return;
  }

  const BRIDGE = "LitePoTokenBridge";

  function bridgeSuccess(requestId, value) {
    global[BRIDGE].onSuccess(requestId, value);
  }

  function bridgeError(requestId, error) {
    const message = error && error.stack ? error.stack : String(error);
    global[BRIDGE].onError(requestId, message);
  }

  function base64ToU8(base64) {
    const base64Mod = base64
      .replace(/-/g, "+")
      .replace(/_/g, "/")
      .replace(/\./g, "=");
    const decoded = atob(base64Mod);
    const bytes = new Uint8Array(decoded.length);
    for (let i = 0; i < decoded.length; i += 1) {
      bytes[i] = decoded.charCodeAt(i);
    }
    return bytes;
  }

  function u8ToBase64(bytes) {
    let binary = "";
    for (let i = 0; i < bytes.length; i += 1) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_");
  }

  function stringToU8(value) {
    if (typeof TextEncoder !== "undefined") {
      return new TextEncoder().encode(value);
    }
    const encoded = unescape(encodeURIComponent(value));
    const bytes = new Uint8Array(encoded.length);
    for (let i = 0; i < encoded.length; i += 1) {
      bytes[i] = encoded.charCodeAt(i);
    }
    return bytes;
  }

  function descrambleChallenge(scrambledChallenge) {
    const decoded = base64ToU8(scrambledChallenge);
    let result = "";
    for (let i = 0; i < decoded.length; i += 1) {
      result += String.fromCharCode((decoded[i] + 97) & 0xff);
    }
    return result;
  }

  function parseRawChallengeData(rawChallengeData) {
    const scrambled = JSON.parse(rawChallengeData);
    const challengeData = Array.isArray(scrambled) && scrambled.length > 1 && typeof scrambled[1] === "string"
      ? JSON.parse(descrambleChallenge(scrambled[1]))
      : scrambled[0];
    const interpreterArray = Array.isArray(challengeData[1]) ? challengeData[1] : null;
    const resourceArray = Array.isArray(challengeData[2]) ? challengeData[2] : null;

    return {
      messageId: challengeData[0],
      interpreterJavascript: {
        privateDoNotAccessOrElseSafeScriptWrappedValue: interpreterArray
          ? interpreterArray.find(item => typeof item === "string")
          : null,
        privateDoNotAccessOrElseTrustedResourceUrlWrappedValue: resourceArray
          ? resourceArray.find(item => typeof item === "string")
          : null
      },
      interpreterHash: challengeData[3],
      program: challengeData[4],
      globalName: challengeData[5],
      clientExperimentsStateBlob: challengeData[7]
    };
  }

  function loadBotGuard(challengeData) {
    this.vm = this[challengeData.globalName];
    this.program = challengeData.program;
    this.vmFunctions = {};
    this.syncSnapshotFunction = null;

    if (!this.vm) {
      throw new Error("[BotGuardClient]: VM not found in the global object");
    }
    if (!this.vm.a) {
      throw new Error("[BotGuardClient]: Could not load program");
    }

    const vmFunctionsCallback = function (
      asyncSnapshotFunction,
      shutdownFunction,
      passEventFunction,
      checkCameraFunction
    ) {
      this.vmFunctions = {
        asyncSnapshotFunction: asyncSnapshotFunction,
        shutdownFunction: shutdownFunction,
        passEventFunction: passEventFunction,
        checkCameraFunction: checkCameraFunction
      };
    };

    this.syncSnapshotFunction = this.vm.a(
      this.program,
      vmFunctionsCallback,
      true,
      this.userInteractionElement,
      function () {},
      [[], []]
    )[0];

    return new Promise(function (resolve, reject) {
      let i = 0;
      const refreshIntervalId = setInterval(function () {
        if (!!this.vmFunctions.asyncSnapshotFunction) {
          resolve(this);
          clearInterval(refreshIntervalId);
        }
        if (i >= 10000) {
          reject(new Error("asyncSnapshotFunction is null even after 10 seconds"));
          clearInterval(refreshIntervalId);
        }
        i += 1;
      }, 1);
    });
  }

  function snapshot(args) {
    return new Promise(function (resolve, reject) {
      if (!this.vmFunctions.asyncSnapshotFunction) {
        reject(new Error("[BotGuardClient]: Async snapshot function not found"));
        return;
      }

      this.vmFunctions.asyncSnapshotFunction(function (response) {
        resolve(response);
      }, [
        args.contentBinding,
        args.signedTimestamp,
        args.webPoSignalOutput,
        args.skipPrivacyBuffer
      ]);
    });
  }

  function runBotGuard(challengeData) {
    const interpreterJavascript =
      challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
    if (!interpreterJavascript) {
      throw new Error("Could not load VM");
    }

    new Function(interpreterJavascript)();
    const webPoSignalOutput = [];
    return loadBotGuard({
      globalName: challengeData.globalName,
      globalObj: this,
      program: challengeData.program
    }).then(function (botguard) {
      return snapshot.call(botguard, { webPoSignalOutput: webPoSignalOutput });
    }).then(function (botguardResponse) {
      return {
        webPoSignalOutput: webPoSignalOutput,
        botguardResponse: botguardResponse
      };
    });
  }

  function obtainPoToken(webPoSignalOutput, integrityToken, identifier) {
    const getMinter = webPoSignalOutput[0];
    if (!getMinter) {
      throw new Error("PMD:Undefined");
    }
    const mintCallback = getMinter(integrityToken);
    if (!(mintCallback instanceof Function)) {
      throw new Error("APF:Failed");
    }
    const result = mintCallback(identifier);
    if (!result) {
      throw new Error("YNJ:Undefined");
    }
    if (!(result instanceof Uint8Array)) {
      throw new Error("ODM:Invalid");
    }
    return result;
  }

  global.__litePoToken = {
    webPoSignalOutput: null,
    integrityToken: null,

    runInit: function (rawChallengeData, requestId) {
      try {
        const challengeData = parseRawChallengeData(rawChallengeData);
        runBotGuard(challengeData).then(function (result) {
          global.__litePoToken.webPoSignalOutput = result.webPoSignalOutput;
          bridgeSuccess(requestId, result.botguardResponse);
        }, function (error) {
          bridgeError(requestId, error);
        });
      } catch (error) {
        bridgeError(requestId, error);
      }
    },

    setIntegrityToken: function (integrityTokenBase64) {
      global.__litePoToken.integrityToken = base64ToU8(integrityTokenBase64);
      return true;
    },

    mint: function (identifier, requestId) {
      try {
        const poToken = obtainPoToken(
          global.__litePoToken.webPoSignalOutput,
          global.__litePoToken.integrityToken,
          stringToU8(identifier)
        );
        bridgeSuccess(requestId, u8ToBase64(poToken));
      } catch (error) {
        bridgeError(requestId, error);
      }
    }
  };
})(window);
