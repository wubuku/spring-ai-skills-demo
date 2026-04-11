#!/bin/bash
# Test script for SSE streaming transcription endpoint
# Usage: ./test-streaming-transcribe.sh [audio_file]
# If no audio file is provided, uses a test WAV file

set -e

AUDIO_FILE="${1:-/tmp/test_audio.wav}"

echo "=========================================="
echo "SSE Streaming Transcription Test"
echo "=========================================="
echo ""
echo "Testing endpoint: http://localhost:8080/api/transcribe/stream"
echo "Audio file: $AUDIO_FILE"
echo ""

if [ ! -f "$AUDIO_FILE" ]; then
    echo "Error: Audio file not found: $AUDIO_FILE"
    exit 1
fi

echo "Sending request..."
echo ""

# Send the request and capture the output
RESPONSE=$(curl -s -N --max-time 30 -X POST "http://localhost:8080/api/transcribe/stream" \
  -H "Accept: text/event-stream" \
  -F "audio=@$AUDIO_FILE" 2>&1)

echo "Response:"
echo "----------------------------------------"
echo "$RESPONSE"
echo "----------------------------------------"
echo ""

# Check if we got any SSE events
if echo "$RESPONSE" | grep -q "data:"; then
    echo ""
    echo "SUCCESS: Received SSE events from the streaming transcription endpoint!"

    # Count the number of SSE events
    EVENT_COUNT=$(echo "$RESPONSE" | grep -c "data:")
    echo "Total SSE events received: $EVENT_COUNT"

    # Extract transcription text from the final transcript
    if echo "$RESPONSE" | grep -q '"type":"transcribed"'; then
        echo ""
        echo "Transcription content found in response."
    fi

    exit 0
else
    echo ""
    echo "FAILURE: No SSE events received"
    exit 1
fi