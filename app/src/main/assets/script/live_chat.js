// refers: https://greasyfork.org/en/scripts/519614-live-chat-on-youtube-mobile
(function () {
    'use strict';
    if (!JSON.parse(android.getPreferences()).enable_live_chat) return;

    setInterval(() => {
        const isLive = document.querySelector('#movie_player')?.getPlayerResponse()?.playabilityStatus?.liveStreamability &&
            location.href.toLowerCase().startsWith('https://m.youtube.com/watch');
        show_live_chat(isLive);
    }, 1000);

    function values(key) {
        const languages = {
            'zh': { "Show Live Chat": "显示直播聊天", "Hide Live Chat": "隐藏直播聊天" },
            'en': { "Show Live Chat": "Show Live Chat", "Hide Live Chat": "Hide Live Chat" },
            'ja': { "Show Live Chat": "ライブチャットを表示", "Hide Live Chat": "ライブチャットを隠す" },
            'ko': { "Show Live Chat": "라이브 채팅 표시", "Hide Live Chat": "라이브 채팅 숨기기" },
            'fr': { "Show Live Chat": "Afficher le chat en direct", "Hide Live Chat": "Masquer le chat en direct" }
        };
        const lang = (document.body.lang || 'en').substring(0, 2).toLowerCase();
        return languages[lang]?.[key] || languages['en'][key];
    }

    function show_live_chat(isLive) {
        // Create a button to show/hide the chat
        let button = document.querySelector("#show_live_chat_button");
        if (!button) {
            button = document.createElement('button');
            button.id = "show_live_chat_button";
            button.innerText = values('Show Live Chat');
            button.style.position = 'fixed';
            button.style.bottom = '50px'; // Adjust the position
            button.style.right = '20px';
            button.style.zIndex = '9999'; // Ensures the button is always on top
            button.style.padding = '10px';
            button.style.backgroundColor = '#ff0000';
            button.style.color = '#fff';
            button.style.border = 'none';
            button.style.borderRadius = '5px';
            button.style.cursor = 'pointer';
            button.style.display = 'none';
        
            // Add the button to the document body
            document.body.appendChild(button);
        }

    
        // Create a container for the chat
        let chatContainer = document.querySelector("#live_chat_container");
        if (!chatContainer) {
            chatContainer = document.createElement('div');
            chatContainer.id = "live_chat_container";
            chatContainer.style.display = 'none'; // Hidden by default
            chatContainer.style.position = 'absolute'; // Use absolute positioning to place it below the video
            chatContainer.style.width = 'calc(100% - 4px)'; // Adjust width to fill the screen minus the margin
            chatContainer.style.height = '420px'; // Adjust height
            chatContainer.style.border = '1px solid #ccc';
            chatContainer.style.backgroundColor = '#fff';
            chatContainer.style.zIndex = '1000'; // Ensure the chat is below the button
            chatContainer.style.overflow = 'auto'; // Allow scrolling if the chat is long
            chatContainer.style.marginLeft = '2px'; // 2px margin on the left
            chatContainer.style.marginRight = '2px'; // 2px margin on the right
            chatContainer.style.setProperty("border-radius", "10px");
        }
        
    
        // Function to toggle the chat visibility
        button.onclick = function() {
            if (chatContainer.style.display === 'none') {
                const videoIdMatch = window.location.search.match(/v=([^&]+)/);
                if (videoIdMatch) {
                    const oldIframe = chatContainer.querySelector("#chatIframe");
                    if (oldIframe) {
                        chatContainer.removeChild(oldIframe);
                    }
                    // Create the iframe for the chat
                    const chatIframe = document.createElement('iframe');
                    chatIframe.id = "chatIframe";
                    chatIframe.style.width = '100%';
                    chatIframe.style.height = '100%';
                    chatIframe.style.border = 'none';
                    chatIframe.style.maxWidth = '100%'; // Ensure the iframe doesn't overflow the container
                    const liveId = videoIdMatch[1];
                    chatIframe.src = `https://www.youtube.com/live_chat?v=${liveId}&embed_domain=${window.location.hostname}`;
                    chatContainer.appendChild(chatIframe);
                    document.body.appendChild(chatContainer); // Append the chat container to the body
    
                    // Position the chat below the video player
                    const videoPlayer = document.querySelector('video');
                    if (videoPlayer) {
                        const videoRect = videoPlayer.getBoundingClientRect();
                        chatContainer.style.top = `${videoRect.bottom + window.scrollY}px`; // Position the chat just below the video
                    }
    
                    chatContainer.style.display = 'block'; // Show the chat container
                    button.innerText = values('Hide Live Chat'); // Change the button text to "Hide Live Chat"
                }
            } else {
                chatContainer.style.display = 'none'; // Hide the chat container
                button.innerText = values('Show Live Chat'); 
            }
        };
        if (isLive) {
            button.style.display = 'inline-block';
        } else {
            button.style.display = 'none';
            chatContainer.style.display = 'none';
        }
    }

})();
