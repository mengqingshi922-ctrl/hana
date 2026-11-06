Perform a complete autonomous social media interaction loop on a post/video in one step:
1. If currently on a search or feeds list, automatically selects the next unhandled post card (or taps postRef), entering its detail page. If all visible posts are handled, it automatically scrolls down to load fresh posts.
2. In the post detail page: executes idempotent like (never unlikes if already liked), simulates human reading delay (0.8-1.5s) to avoid anti-bot frequency limits, automatically posts the specified comment text (with anti-duplicate variations), and clicks Send.
3. Automatically closes overlays and navigates back to the feeds list so you are immediately ready for the next post.
4. Maintains an in-task deduplication tracker, returning completed count and remaining post hints.

Always prefer phone_social_interact over separate manual steps when performing mutual likes (互赞), batch commenting, or video/post interaction routines.