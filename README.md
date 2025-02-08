Deep research loop implemented in babashka. 

Loosely inspired by [`dzhng/deep-research`](https://github.com/dzhng/deep-research)

```
bb research.clj --query 'what is the best way to train a dog' \
                --output-dir dog-training \
                --verbose
```

Requires [`llm`](https://llm.datasette.io/en/stable/).

You can change the model used by setting the `default-model` var in `research.clj`.

To see models you have setup for `llm` run `llm models`.